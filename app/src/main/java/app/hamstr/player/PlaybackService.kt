package app.hamstr.player

import android.app.PendingIntent
import android.content.Intent
import android.media.AudioAttributes as SystemAudio
import android.media.AudioManager
import android.media.AudioPlaybackConfiguration
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import androidx.core.content.IntentCompat
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
import app.hamstr.data.DownloadStore
import app.hamstr.data.Positions
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import app.hamstr.data.Prefs
import app.hamstr.widget.PlayerWidgets
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Owns the player. Media3 turns its session into the media notification and keeps playing in
 * the background (audio, or a video's sound when the app is left). During a crossfade a
 * second player starts the next song and the session moves over to it ([Crossfade]).
 */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class PlaybackService : MediaSessionService() {
    override fun attachBaseContext(base: android.content.Context) = super.attachBaseContext(app.hamstr.AppLanguage.wrap(base))

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
        val id = player.currentMediaItem?.mediaId?.takeUnless { it.startsWith(app.hamstr.cast.HamstrCast.REMOTE) } ?: return
        if (player.duration > 0) Positions.save(id, player.currentPosition, player.duration)
    }

    private val attributes = AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MOVIE).build()
    private val crossfade = Crossfade(handler, active = { session?.player as? ExoPlayer }, spawn = { newPlayer(main = false) }, swap = ::swap)

    /**
     * A player with the equalizer. Only the one the session holds takes the audio focus and stops
     * for unplugged headphones. Plain http goes to [LanDataSource]: only streams another Hamstr plays here.
     */
    private fun newPlayer(main: Boolean): ExoPlayer = ExoPlayer.Builder(this, EqRenderers(this))
        .setMediaSourceFactory(androidx.media3.exoplayer.source.DefaultMediaSourceFactory(androidx.media3.datasource.DefaultDataSource.Factory(this, LanDataSource.Factory())))
        .setAudioAttributes(attributes, /* handleAudioFocus = */ main)
        .setHandleAudioBecomingNoisy(main)
        .setSeekBackIncrementMs(10_000)
        .setSeekForwardIncrementMs(10_000)
        .build()

    /** A crossfade began: the session, the focus and the listener go to [next]; [old] plays its last seconds out. */
    private fun swap(old: ExoPlayer, next: ExoPlayer) {
        old.removeListener(listener)
        old.setAudioAttributes(attributes, false)
        old.setHandleAudioBecomingNoisy(false)
        next.setAudioAttributes(attributes, true)
        next.setHandleAudioBecomingNoisy(true)
        next.addListener(listener)
        last = next.currentMediaItem?.mediaId
        session?.player = next
        PlayerWidgets.update(this, next)
    }

    // ---- playing on a TV or speaker ----------------------------------------------------
    // While Hamstr casts, the session holds a CastPlayer; ExoPlayer waits paused and takes
    // over again where the device was when the cast ends.
    private val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Main.immediate)
    private var local: ExoPlayer? = null

    private fun follow(cast: app.hamstr.cast.CastSession?) {
        val now = session?.player ?: return
        if (cast != null && (now as? CastPlayer)?.cast !== cast) {
            val exo = (now as? ExoPlayer) ?: local ?: return
            val from = now
            val items = List(from.mediaItemCount) { from.getMediaItemAt(it) }
            if (items.isEmpty()) return
            crossfade.release()
            val player = CastPlayer(cast, items, from.currentMediaItemIndex, from.currentPosition, from.playWhenReady, from.repeatMode, from.shuffleModeEnabled)
            exo.pause()
            from.removeListener(listener)
            (from as? CastPlayer)?.release()
            local = exo
            player.addListener(listener)
            session?.player = player
            PlayerWidgets.update(this, player)
        } else if (cast == null && now is CastPlayer) {
            val exo = local ?: return
            val items = now.queue
            val same = items.map { it.mediaId } == List(exo.mediaItemCount) { exo.getMediaItemAt(it).mediaId }
            if (items.isNotEmpty()) {
                if (same) exo.seekTo(now.currentIndex, now.positionMs) else exo.setMediaItems(items, now.currentIndex, now.positionMs)
                exo.prepare()
            }
            exo.pause()
            now.removeListener(listener)
            exo.addListener(listener)
            session?.player = exo
            local = null
            now.release()
            PlayerWidgets.update(this, exo)
        }
    }

    private var last: String? = null
    private val listener = object : Player.Listener {
        override fun onShuffleModeEnabledChanged(shuffleModeEnabled: Boolean) {
            Prefs.edit { it.copy(playbackShuffle = shuffleModeEnabled) }
        }
        override fun onRepeatModeChanged(repeatMode: Int) {
            Prefs.edit { it.copy(playbackRepeat = repeatMode) }
        }
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            handler.removeCallbacks(keep)
            if (isPlaying) handler.postDelayed(keep, 5_000) else session?.player?.let(::remember)
            crossfade.watch(isPlaying)
        }
        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            // Played to the end and on to the next one: the last one starts over next time.
            if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO) last?.let(Positions::clear)
            last = mediaItem?.mediaId
            // A stream from another Hamstr keeps the Wi-Fi awake while it plays, screen off too.
            (session?.player as? ExoPlayer)?.setWakeMode(if (mediaItem?.mediaId?.startsWith(app.hamstr.cast.HamstrCast.REMOTE) == true) C.WAKE_MODE_NETWORK else C.WAKE_MODE_NONE)
        }
        override fun onPlaybackStateChanged(state: Int) {
            if (state == Player.STATE_ENDED) session?.player?.currentMediaItem?.mediaId?.let(Positions::clear)
        }
        override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
            handler.removeCallbacks(resume)
            if (!playWhenReady && reason == Player.PLAY_WHEN_READY_CHANGE_REASON_AUDIO_FOCUS_LOSS) {
                yieldedAt = System.currentTimeMillis(); interruption.reset()
            } else yieldedAt = 0
        }
        override fun onEvents(player: Player, events: Player.Events) {
            if (events.containsAny(Player.EVENT_PLAY_WHEN_READY_CHANGED, Player.EVENT_MEDIA_ITEM_TRANSITION, Player.EVENT_MEDIA_METADATA_CHANGED, Player.EVENT_TIMELINE_CHANGED)) {
                PlayerWidgets.update(this@PlaybackService, player)
            }
        }
    }

    // Headphones like Sony's XM5 send Play or Pause by what they think the player does, not
    // a toggle; when they think it is paused, their tap sends Play into a song that already
    // plays and nothing happens. A Play from outside while playing pauses instead.
    private val buttons = object : MediaSession.Callback {
        override fun onMediaButtonEvent(session: MediaSession, controllerInfo: MediaSession.ControllerInfo, intent: Intent): Boolean {
            val key = IntentCompat.getParcelableExtra(intent, Intent.EXTRA_KEY_EVENT, KeyEvent::class.java) ?: return false
            val player = session.player
            val playing = player.playWhenReady && player.playbackState != Player.STATE_IDLE && player.playbackState != Player.STATE_ENDED
            if (key.keyCode != KeyEvent.KEYCODE_MEDIA_PLAY || !playing || session.isMediaNotificationController(controllerInfo)) return false
            if (key.action == KeyEvent.ACTION_DOWN && key.repeatCount == 0) player.pause()
            return true
        }

        // An assistant talking to the session ("play … on Hamstr" while Hamstr is the media app)
        // sends words or ids, not files: they become the library's files here, and the app's
        // player follows the queue they make.
        override fun onSetMediaItems(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
            mediaItems: MutableList<MediaItem>,
            startIndex: Int,
            startPositionMs: Long,
        ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> {
            if (mediaItems.all { it.localConfiguration != null }) return super.onSetMediaItems(session, controller, mediaItems, startIndex, startPositionMs)
            val first = mediaItems.firstOrNull()
            val hit = first?.requestMetadata?.searchQuery?.let { VoiceSearch.inLibrary(it, first.requestMetadata.extras) }
                ?: first?.mediaId?.takeIf { it.isNotEmpty() }?.let(DownloadStore::get)?.let { t -> VoiceSearch.Hit(t, listOf(t)) }
                ?: (if (first?.requestMetadata?.searchQuery != null) null else VoiceSearch.inLibrary(null))
                ?: return Futures.immediateFailedFuture(UnsupportedOperationException("Nothing in the library matches"))
            val queue = hit.queue.filter { it.filePath?.let { p -> java.io.File(p).isFile } == true }.ifEmpty { listOf(hit.start) }
            Playback.adopt(this@PlaybackService, queue, hit.start)
            return Futures.immediateFuture(
                MediaSession.MediaItemsWithStartPosition(
                    queue.map { Playback.item(it, java.io.File(it.filePath!!)) },
                    queue.indexOfFirst { it.id == hit.start.id }.coerceAtLeast(0),
                    Positions.resumeAt(hit.start.id),
                ),
            )
        }
    }

    override fun onCreate() {
        super.onCreate()
        val player = newPlayer(main = true)
        player.shuffleModeEnabled = Prefs.settings.value.playbackShuffle
        player.repeatMode = Prefs.settings.value.playbackRepeat
        val open = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        session = MediaSession.Builder(this, player).setSessionActivity(open).setCallback(buttons).build()
        getSystemService(AudioManager::class.java)?.registerAudioPlaybackCallback(others, handler)
        // The hamster, not Media3's play triangle, in the status bar and the system's media pill.
        setMediaNotificationProvider(DefaultMediaNotificationProvider.Builder(this).build().apply { setSmallIcon(R.drawable.ic_stat_hamstr) })
        player.addListener(listener)
        scope.launch { app.hamstr.cast.Cast.session.collect(::follow) }
    }

    // ---- giving way to other apps ------------------------------------------------------
    // ExoPlayer pauses when another app takes the sound. A music app keeps it; a video
    // (YouTube, TikTok, Reels) only borrows it: once nothing else has played for a moment,
    // the song carries on only for positively identified video audio, within half an hour.
    // Music, podcasts and unknown/redacted audio configurations require an explicit play.
    private var yieldedAt = 0L
    private val interruption = PlaybackInterruption()
    private val resume = Runnable {
        val player = session?.player ?: return@Runnable
        if (yieldedAt > 0 && interruption.mayResume && Prefs.settings.value.resumeAfterVideos &&
            System.currentTimeMillis() - yieldedAt < 30 * 60_000L && !player.playWhenReady && player.mediaItemCount > 0
        ) player.play()
        yieldedAt = 0
    }
    private val others = object : AudioManager.AudioPlaybackCallback() {
        override fun onPlaybackConfigChanged(configs: MutableList<AudioPlaybackConfiguration>) {
            if (yieldedAt == 0L) return
            handler.removeCallbacks(resume)
            if (configs.isNotEmpty()) {
                interruption.observe(configs.map { config ->
                    val attrs = config.audioAttributes
                    if (attrs.usage == SystemAudio.USAGE_MEDIA && attrs.contentType == SystemAudio.CONTENT_TYPE_MOVIE)
                        PlaybackInterruption.Source.Video
                    else PlaybackInterruption.Source.MusicOrUnknown
                })
            } else if (interruption.mayResume) handler.postDelayed(resume, 2_500)
        }
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session

    /** Swiping the app away stops playback unless something is actually playing. */
    override fun onTaskRemoved(rootIntent: Intent?) {
        val player = session?.player
        if (player == null || !player.playWhenReady || player.mediaItemCount == 0) stopSelf()
    }

    override fun onDestroy() {
        scope.cancel()
        if (session?.player is CastPlayer) {
            app.hamstr.cast.Cast.disconnect()
            follow(null)
        }
        crossfade.release()
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
