package app.hamstr.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.os.Build
import android.view.View
import android.widget.RemoteViews
import app.hamstr.MainActivity
import app.hamstr.R
import app.hamstr.data.DownloadStore
import app.hamstr.data.Latest
import app.hamstr.data.Prefs
import app.hamstr.data.Status
import app.hamstr.engine.DownloadService
import app.hamstr.ui.formatBytes
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.sample
import kotlinx.coroutines.launch

/** Home screen widget: what Hamstr is doing right now, plus paste and add in one tap. */
class HamstrWidget : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        Widgets.placed(true)
        manager.updateAppWidget(ids, Widgets.views(context))
    }

    override fun onDisabled(context: Context) = Widgets.placed(false)
}

object Widgets {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** Keeps placed widgets in step with the queue, at most once a second. */
    @OptIn(FlowPreview::class)
    fun start(context: Context) {
        val app = context.applicationContext
        scope.launch {
            combine(DownloadStore.tasks, DownloadStore.live, Prefs.settings.map { it.accent }.distinctUntilChanged()) { _, _, _ -> }
                .sample(1000)
                .collect { refresh(app) }
        }
        // The other widgets only change with the accent (the player widget also hears from the player).
        scope.launch {
            Prefs.settings.map { it.accent }.distinctUntilChanged().collect {
                PlayerWidgets.refresh(app)
                PlayerWidgets.refreshMark(app)
            }
        }
    }

    // Whether a Hamstr widget is on a home screen: null until asked once, then kept by the provider's callbacks.
    private var placed: Boolean? = null
    // What the placed widgets show now: the same (a sample with no visible change) isn't sent again.
    private val shown = Latest<Card>()

    /** The provider heard of widgets being placed or the last one going. */
    @Synchronized
    fun placed(on: Boolean) {
        placed = on
        shown.forget()
    }

    /** Asks the system once; a callback that came first (or meanwhile, it waits for this lock) wins. */
    @Synchronized
    private fun placed(manager: AppWidgetManager, component: ComponentName): Boolean =
        placed ?: manager.getAppWidgetIds(component).isNotEmpty().also { placed = it }

    fun refresh(context: Context) {
        val manager = AppWidgetManager.getInstance(context)
        val component = ComponentName(context, HamstrWidget::class.java)
        // No widget placed: no call into the system each second while downloads run.
        if (!placed(manager, component)) return
        val card = card(context)
        shown.send(card) {
            manager.updateAppWidget(component, views(context, card))
            true
        }
    }

    /** What the widget says, worked out before any RemoteViews (with the mark's bitmap) are made. */
    private data class Card(val title: String, val line: String, val progress: Int, val accent: Int)

    private fun card(context: Context): Card {
        // Ticks change only the progress: the list's part is the summary worked out once per list.
        val summary = DownloadStore.summary()
        val running = summary.running
        val queued = summary.queued.size
        val current = running.firstOrNull()?.let { DownloadStore.current(it) }
        val accent = Prefs.settings.value.accent
        if (current != null) {
            val more = running.size - 1 + queued
            return Card(
                current.title,
                // While FFmpeg works or the queue waits, that is what it says.
                DownloadService.stageText(context, current)
                    ?: listOf("${(current.progress * 100).toInt()}%", current.speed, DownloadService.etaLeft(context, current.etaSec), if (more > 0) context.getString(R.string.widget_more, more) else "")
                        .filter { it.isNotEmpty() }.joinToString(" · "),
                (current.progress * 1000).toInt(), accent,
            )
        }
        val newest = summary.newestDone
        return Card(
            context.getString(R.string.widget_stashed, formatBytes(summary.doneBytes)),
            when {
                queued > 0 -> summary.queued.firstOrNull { it.stage != null }?.let { DownloadService.stageText(context, it) } ?: context.getString(R.string.widget_waiting, queued)
                newest == null -> context.getString(R.string.widget_start)
                else -> context.resources.getQuantityString(R.plurals.widget_files, summary.done, summary.done, newest.title)
            },
            -1, accent,
        )
    }

    fun views(context: Context): RemoteViews = views(context, card(context))

    private fun views(context: Context, card: Card): RemoteViews {
        val v = RemoteViews(context.packageName, R.layout.widget_hamstr)
        v.setTextViewText(R.id.widget_title, card.title)
        v.setTextViewText(R.id.widget_line, card.line)
        if (card.progress >= 0) {
            v.setViewVisibility(R.id.widget_progress, View.VISIBLE)
            v.setProgressBar(R.id.widget_progress, 1000, card.progress, false)
        } else {
            v.setViewVisibility(R.id.widget_progress, View.GONE)
        }
        v.setImageViewBitmap(R.id.widget_mark, PlayerWidgets.markBitmap(context, card.accent))
        if (Build.VERSION.SDK_INT >= 31) {
            val accent = ColorStateList.valueOf(card.accent)
            v.setColorStateList(R.id.widget_add, "setBackgroundTintList", accent)
            v.setColorStateList(R.id.widget_progress, "setProgressTintList", accent)
        }
        v.setOnClickPendingIntent(R.id.widget_root, open(context, null, 0))
        v.setOnClickPendingIntent(R.id.widget_paste, open(context, MainActivity.ACTION_PASTE, 1))
        v.setOnClickPendingIntent(R.id.widget_add, open(context, MainActivity.ACTION_ADD, 2))
        return v
    }

    private fun open(context: Context, action: String?, code: Int): PendingIntent = PendingIntent.getActivity(
        context, code,
        Intent(context, MainActivity::class.java).setAction(action ?: Intent.ACTION_MAIN)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )
}
