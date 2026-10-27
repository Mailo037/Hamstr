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
        manager.updateAppWidget(ids, Widgets.views(context))
    }
}

object Widgets {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** Keeps placed widgets in step with the queue, at most once a second. */
    @OptIn(FlowPreview::class)
    fun start(context: Context) {
        val app = context.applicationContext
        scope.launch {
            combine(DownloadStore.tasks, Prefs.settings.map { it.accent }.distinctUntilChanged()) { _, _ -> }
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

    fun refresh(context: Context) {
        val manager = AppWidgetManager.getInstance(context)
        val ids = manager.getAppWidgetIds(ComponentName(context, HamstrWidget::class.java))
        if (ids.isNotEmpty()) manager.updateAppWidget(ids, views(context))
    }

    fun views(context: Context): RemoteViews {
        val v = RemoteViews(context.packageName, R.layout.widget_hamstr)
        val tasks = DownloadStore.tasks.value
        val running = tasks.filter { it.status == Status.Running }
        val queued = tasks.count { it.status == Status.Queued }
        val current = running.firstOrNull()
        if (current != null) {
            v.setTextViewText(R.id.widget_title, current.title)
            val more = running.size - 1 + queued
            v.setTextViewText(
                R.id.widget_line,
                // While FFmpeg works or the queue waits, that is what it says.
                DownloadService.stageText(context, current)
                    ?: listOf("${(current.progress * 100).toInt()}%", current.speed, DownloadService.eta(current.etaSec), if (more > 0) context.getString(R.string.widget_more, more) else "")
                        .filter { it.isNotEmpty() }.joinToString(" · "),
            )
            v.setViewVisibility(R.id.widget_progress, View.VISIBLE)
            v.setProgressBar(R.id.widget_progress, 1000, (current.progress * 1000).toInt(), false)
        } else {
            val done = tasks.filter { it.status == Status.Done }
            v.setTextViewText(R.id.widget_title, context.getString(R.string.widget_stashed, formatBytes(done.sumOf { it.sizeBytes })))
            v.setTextViewText(
                R.id.widget_line,
                when {
                    queued > 0 -> tasks.firstOrNull { it.status == Status.Queued && it.stage != null }?.let { DownloadService.stageText(context, it) } ?: context.getString(R.string.widget_waiting, queued)
                    done.isEmpty() -> context.getString(R.string.widget_start)
                    else -> context.resources.getQuantityString(R.plurals.widget_files, done.size, done.size, done.first().title)
                },
            )
            v.setViewVisibility(R.id.widget_progress, View.GONE)
        }
        v.setImageViewBitmap(R.id.widget_mark, PlayerWidgets.markBitmap(context, Prefs.settings.value.accent))
        if (Build.VERSION.SDK_INT >= 31) {
            val accent = ColorStateList.valueOf(Prefs.settings.value.accent)
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
