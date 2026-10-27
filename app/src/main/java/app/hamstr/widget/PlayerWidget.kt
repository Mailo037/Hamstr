package app.hamstr.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.os.Build
import android.widget.RemoteViews
import androidx.core.content.ContextCompat
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import app.hamstr.MainActivity
import app.hamstr.R
import app.hamstr.data.Prefs
import app.hamstr.player.PlaybackService
import app.hamstr.ui.AppIcon
import coil3.imageLoader
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.request.allowHardware
import coil3.toBitmap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/** What the player widget shows; the playback service keeps it current. */
data class NowPlaying(val title: String, val artist: String?, val artwork: String?, val playing: Boolean, val hasNext: Boolean, val hasPrevious: Boolean)

/** Home screen widget: what plays, with previous, play/pause and next. Tapping it opens the player. */
class PlayerWidget : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) = PlayerWidgets.refresh(context)
}

/** The hamster alone, cheeks in the accent: one tap stashes the copied link. */
class MarkWidget : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) = PlayerWidgets.refreshMark(context)
}

object PlayerWidgets {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    @Volatile private var now: NowPlaying? = null
    private var cover: Pair<String, Bitmap>? = null
    private var mark: Pair<Int, Bitmap>? = null

    /** Called by the playback service whenever what plays, or whether it plays, changes. */
    fun update(context: Context, player: Player?) {
        val item = player?.currentMediaItem
        now = item?.let {
            val m = player.mediaMetadata
            NowPlaying(m.title?.toString() ?: "", m.artist?.toString(), m.artworkUri?.toString(), player.playWhenReady, player.hasNextMediaItem(), player.hasPreviousMediaItem())
        }
        refresh(context.applicationContext)
    }

    fun refresh(context: Context) {
        val manager = AppWidgetManager.getInstance(context)
        val ids = manager.getAppWidgetIds(ComponentName(context, PlayerWidget::class.java))
        if (ids.isEmpty()) return
        val n = now
        // Text and buttons right away; the cover follows once it is loaded.
        manager.updateAppWidget(ids, views(context, n, cover?.takeIf { it.first == n?.artwork }?.second))
        val art = n?.artwork ?: return
        if (cover?.first == art) return
        scope.launch {
            val request = ImageRequest.Builder(context).data(art).size(192).allowHardware(false).build()
            val bmp = (context.imageLoader.execute(request) as? SuccessResult)?.image?.toBitmap() ?: return@launch
            cover = art to bmp
            if (now?.artwork == art) manager.updateAppWidget(ids, views(context, now, bmp))
        }
    }

    private fun views(context: Context, n: NowPlaying?, art: Bitmap?): RemoteViews {
        val v = RemoteViews(context.packageName, R.layout.widget_player)
        val accent = Prefs.settings.value.accent
        if (n == null) {
            v.setTextViewText(R.id.player_title, context.getString(R.string.widget_nothing_playing))
            v.setTextViewText(R.id.player_artist, context.getString(R.string.widget_open_library))
            v.setImageViewBitmap(R.id.player_cover, markBitmap(context, accent))
        } else {
            v.setTextViewText(R.id.player_title, n.title)
            v.setTextViewText(R.id.player_artist, n.artist ?: "")
            if (art != null) v.setImageViewBitmap(R.id.player_cover, art) else v.setImageViewBitmap(R.id.player_cover, markBitmap(context, accent))
        }
        val playing = n?.playing == true
        v.setImageViewResource(R.id.player_play, if (playing) R.drawable.ic_pause else R.drawable.ic_play_arrow)
        v.setContentDescription(R.id.player_play, context.getString(if (playing) R.string.player_pause else R.string.player_play))
        v.setInt(R.id.player_prev, "setImageAlpha", if (n?.hasPrevious == true) 255 else 90)
        v.setInt(R.id.player_next, "setImageAlpha", if (n?.hasNext == true) 255 else 90)
        if (Build.VERSION.SDK_INT >= 31) {
            v.setColorStateList(R.id.player_play, "setBackgroundTintList", ColorStateList.valueOf(accent))
        }
        v.setOnClickPendingIntent(R.id.player_root, open(context, if (n == null) MainActivity.ACTION_LIBRARY else MainActivity.ACTION_PLAYER, 10))
        v.setOnClickPendingIntent(R.id.player_play, action(context, WidgetActions.PLAY_PAUSE, 11))
        v.setOnClickPendingIntent(R.id.player_next, action(context, WidgetActions.NEXT, 12))
        v.setOnClickPendingIntent(R.id.player_prev, action(context, WidgetActions.PREVIOUS, 13))
        return v
    }

    fun refreshMark(context: Context) {
        val manager = AppWidgetManager.getInstance(context)
        val ids = manager.getAppWidgetIds(ComponentName(context, MarkWidget::class.java))
        if (ids.isEmpty()) return
        val v = RemoteViews(context.packageName, R.layout.widget_mark)
        v.setImageViewBitmap(R.id.mark_image, markBitmap(context, Prefs.settings.value.accent))
        v.setOnClickPendingIntent(R.id.mark_root, open(context, MainActivity.ACTION_PASTE, 20))
        manager.updateAppWidget(ids, v)
    }

    /** The hamster with accent cheeks, drawn once per accent. */
    @Synchronized
    fun markBitmap(context: Context, accent: Int): Bitmap =
        mark?.takeIf { it.first == accent }?.second ?: AppIcon.mark(context, 192, accent).also { mark = accent to it }

    private fun open(context: Context, action: String, code: Int): PendingIntent = PendingIntent.getActivity(
        context, code,
        Intent(context, MainActivity::class.java).setAction(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    private fun action(context: Context, action: String, code: Int): PendingIntent = PendingIntent.getBroadcast(
        context, code,
        Intent(context, WidgetActions::class.java).setAction(action),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )
}

/** The player widget's buttons: they reach the player through a short-lived controller. */
class WidgetActions : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        val pending = goAsync()
        val app = context.applicationContext
        val future = MediaController.Builder(app, SessionToken(app, ComponentName(app, PlaybackService::class.java))).buildAsync()
        future.addListener({
            val c = runCatching { future.get() }.getOrNull()
            try {
                if (c == null || c.mediaItemCount == 0) {
                    // Nothing loaded: the library is where something gets picked.
                    app.startActivity(Intent(app, MainActivity::class.java).setAction(MainActivity.ACTION_LIBRARY).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                } else when (action) {
                    PLAY_PAUSE -> if (c.playWhenReady) c.pause() else { if (c.playbackState == Player.STATE_IDLE) c.prepare(); c.play() }
                    NEXT -> c.seekToNextMediaItem()
                    PREVIOUS -> c.seekToPrevious()
                }
            } finally {
                MediaController.releaseFuture(future)
                pending.finish()
            }
        }, ContextCompat.getMainExecutor(app))
    }

    companion object {
        const val PLAY_PAUSE = "app.hamstr.widget.PLAY_PAUSE"
        const val NEXT = "app.hamstr.widget.NEXT"
        const val PREVIOUS = "app.hamstr.widget.PREVIOUS"
    }
}
