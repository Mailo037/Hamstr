package app.hamstr.player

import android.os.Handler
import android.os.SystemClock
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import app.hamstr.data.Positions
import app.hamstr.data.Prefs
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * Songs fading into each other (Settings › Playback › Crossfade). That many seconds before a
 * song ends, a second player starts the queue at the next song, silent, and the session moves
 * over to it; the old player plays out the end while the sound crosses over, then goes. Only
 * from song to song: videos, repeat one and "stop at the end of this song" keep the cut.
 */
@UnstableApi
internal class Crossfade(
    private val handler: Handler,
    private val active: () -> ExoPlayer?,
    private val spawn: () -> ExoPlayer,
    private val swap: (old: ExoPlayer, next: ExoPlayer) -> Unit,
) {
    private class Fade(val old: ExoPlayer, val next: ExoPlayer, val started: Long, val length: Long)

    private var fading: Fade? = null

    private val check = object : Runnable {
        override fun run() {
            tick()
            handler.postDelayed(this, 200)
        }
    }

    private val ramp = object : Runnable {
        override fun run() {
            val f = fading ?: return
            val t = ((SystemClock.uptimeMillis() - f.started).toFloat() / f.length).coerceIn(0f, 1f)
            // Equal power: the two together stay about as loud as one.
            f.next.volume = sin(t * PI / 2).toFloat()
            f.old.volume = cos(t * PI / 2).toFloat()
            // Done, or cut short: the old song ran out, or play was paused.
            if (t >= 1f || f.old.playbackState == Player.STATE_ENDED || !f.old.playWhenReady || !f.next.playWhenReady) finish()
            else handler.postDelayed(this, 30)
        }
    }

    /** Looks for the moment to start while something plays. */
    fun watch(playing: Boolean) {
        handler.removeCallbacks(check)
        if (playing) handler.post(check)
    }

    fun release() {
        handler.removeCallbacks(check)
        finish()
    }

    private fun tick() {
        val p = active() ?: return
        val length = Prefs.settings.value.crossfade * 1000L
        if (length <= 0 || fading != null || !p.isPlaying) return
        val duration = p.duration
        // A song shorter than both fades together just plays.
        if (duration == C.TIME_UNSET || duration < length * 2 + 5_000) return
        val left = ((duration - p.currentPosition) / p.playbackParameters.speed).toLong()
        if (left > length || left < 400) return
        if (p.repeatMode == Player.REPEAT_MODE_ONE || !p.hasNextMediaItem() || Playback.sleep.value is Playback.Sleep.EndOfTrack) return
        val nextIndex = p.nextMediaItemIndex
        if (!song(p.currentMediaItem) || !song(p.getMediaItemAt(nextIndex))) return
        begin(p, nextIndex, left)
    }

    private fun song(item: MediaItem?) = item?.localConfiguration?.mimeType?.startsWith("audio/") == true

    private fun begin(old: ExoPlayer, nextIndex: Int, left: Long) {
        val next = spawn()
        next.setMediaItems(List(old.mediaItemCount) { old.getMediaItemAt(it) }, nextIndex, 0)
        next.repeatMode = old.repeatMode
        next.shuffleModeEnabled = old.shuffleModeEnabled
        next.playbackParameters = old.playbackParameters
        next.volume = 0f
        next.prepare()
        next.play()
        // The old one stops after this song instead of going on with the queue.
        old.pauseAtEndOfMediaItems = true
        old.currentMediaItem?.mediaId?.let(Positions::clear)
        swap(old, next)
        fading = Fade(old, next, SystemClock.uptimeMillis(), left)
        handler.post(ramp)
    }

    private fun finish() {
        val f = fading ?: return
        fading = null
        handler.removeCallbacks(ramp)
        f.next.volume = 1f
        f.old.release()
    }
}
