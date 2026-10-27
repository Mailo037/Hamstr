package app.hamstr.player

import android.app.PendingIntent
import android.content.Intent
import android.media.AudioAttributes as SystemAudio
import android.media.AudioManager
import android.media.AudioPlaybackConfiguration
import android.os.Handler
import android.os.Looper
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.DefaultMediaNotificationProvider
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import app.hamstr.MainActivity
import app.hamstr.R
import app.hamstr.data.Positions
import app.hamstr.data.Prefs
import app.hamstr.widget.PlayerWidgets

/**
 * Owns the one ExoPlayer. Media3 turns its session into the media notification and keeps
 * playing in the background (audio, or a video's sound when the app is left).
 */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
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
        watchFocus(player)
        // The hamster, not Media3's play triangle, in the status bar and the system's media pill.
        setMediaNotificationProvider(DefaultMediaNotificationProvider.Builder(this).build().apply { setSmallIcon(R.drawable.ic_stat_hamstr) })
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
            override fun onEvents(player: Player, events: Player.Events) {
                if (events.containsAny(Player.EVENT_PLAY_WHEN_READY_CHANGED, Player.EVENT_MEDIA_ITEM_TRANSITION, Player.EVENT_MEDIA_METADATA_CHANGED, Player.EVENT_TIMELINE_CHANGED)) {
                    PlayerWidgets.update(this@PlaybackService, player)
                }
            }
        })
    }

    // ---- giving way to other apps ------------------------------------------------------
    // ExoPlayer pauses when another app takes the sound. A music app keeps it; a video
    // (YouTube, TikTok, Reels) only borrows it: once nothing else has played for a moment,
    // the song carries on, as long as that is within half an hour and nobody paused by hand.
    private var yieldedAt = 0L
    private var toMusic = false
    private var heardOther = false
    private val resume = Runnable {
        val player = session?.player ?: return@Runnable
        if (yieldedAt > 0 && !toMusic && heardOther && Prefs.settings.value.resumeAfterVideos &&
            System.currentTimeMillis() - yieldedAt < 30 * 60_000L && !player.playWhenReady && player.mediaItemCount > 0
        ) player.play()
        yieldedAt = 0
    }
    private val others = object : AudioManager.AudioPlaybackCallback() {
        override fun onPlaybackConfigChanged(configs: MutableList<AudioPlaybackConfiguration>) {
            if (yieldedAt == 0L) return
            handler.removeCallbacks(resume)
            if (configs.isNotEmpty()) {
                heardOther = true
                // Songs and podcasts are a choice to listen to something else.
                if (configs.any { it.audioAttributes.contentType == SystemAudio.CONTENT_TYPE_MUSIC || it.audioAttributes.contentType == SystemAudio.CONTENT_TYPE_SPEECH }) toMusic = true
            } else if (heardOther) handler.postDelayed(resume, 2_500)
        }
    }

    private fun watchFocus(player: Player) {
        getSystemService(AudioManager::class.java)?.registerAudioPlaybackCallback(others, handler)
        player.addListener(object : Player.Listener {
            override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
                handler.removeCallbacks(resume)
                if (!playWhenReady && reason == Player.PLAY_WHEN_READY_CHANGE_REASON_AUDIO_FOCUS_LOSS) {
                    yieldedAt = System.currentTimeMillis(); toMusic = false; heardOther = false
                } else yieldedAt = 0
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
        handler.removeCallbacks(resume)
        runCatching { getSystemService(AudioManager::class.java)?.unregisterAudioPlaybackCallback(others) }
        session?.run {
            remember(player)
            player.release()
            release()
        }
        session = null
        PlayerWidgets.update(this, null)
        super.onDestroy()
    }
}
