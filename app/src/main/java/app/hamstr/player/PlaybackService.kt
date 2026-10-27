package app.hamstr.player

import android.app.PendingIntent
import android.content.Intent
import android.os.Handler
import android.os.Looper
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import app.hamstr.MainActivity
import app.hamstr.data.Positions

/**
 * Owns the one ExoPlayer. Media3 turns its session into the media notification and keeps
 * playing in the background (audio, or a video's sound when the app is left).
 */
class PlaybackService : MediaSessionService() {
    private var session: MediaSession? = null
    private val handler = Handler(Looper.getMainLooper())
    // Where each file was left, written every few seconds while it plays and whenever it stops.
    private val keep = object : Runnable {
        override fun run() {
            session?.player?.let(::remember)
            handler.postDelayed(this, 5_000)
        }
    }

    private fun remember(player: Player) {
        val id = player.currentMediaItem?.mediaId ?: return
        if (player.duration > 0) Positions.save(id, player.currentPosition, player.duration)
    }

    override fun onCreate() {
        super.onCreate()
        val player = ExoPlayer.Builder(this)
            .setAudioAttributes(
                AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MOVIE).build(),
                /* handleAudioFocus = */ true,
            )
            .setHandleAudioBecomingNoisy(true)
            .setSeekBackIncrementMs(10_000)
            .setSeekForwardIncrementMs(10_000)
            .build()
        val open = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        session = MediaSession.Builder(this, player).setSessionActivity(open).build()
        player.addListener(object : Player.Listener {
            private var last: String? = null
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                handler.removeCallbacks(keep)
                if (isPlaying) handler.postDelayed(keep, 5_000) else remember(player)
            }
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                // Played to the end and on to the next one: the last one starts over next time.
                if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO) last?.let(Positions::clear)
                last = mediaItem?.mediaId
            }
            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_ENDED) player.currentMediaItem?.mediaId?.let(Positions::clear)
            }
        })
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session

    /** Swiping the app away stops playback unless something is actually playing. */
    override fun onTaskRemoved(rootIntent: Intent?) {
        val player = session?.player
        if (player == null || !player.playWhenReady || player.mediaItemCount == 0) stopSelf()
    }

    override fun onDestroy() {
        handler.removeCallbacks(keep)
        session?.run {
            remember(player)
            player.release()
            release()
        }
        session = null
        super.onDestroy()
    }
}
