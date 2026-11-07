package app.hamstr.player

import android.content.Context
import android.media.AudioManager
import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import app.hamstr.cast.CastStatus
import app.hamstr.cast.HamstrCast
import app.hamstr.data.Choice
import app.hamstr.data.DownloadTask
import app.hamstr.data.Importer
import app.hamstr.data.Mode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import kotlin.math.roundToInt

/**
 * What another Hamstr plays on this phone ("Play on"): its stream goes into the app's own player
 * as a queue of one ([Playback.openStream]), so the notification, the lock screen and the
 * headphones steer it here while the sender steers it from there. The volume is the phone's
 * media volume. Closing the player, or playing something else, hands it back to the sender.
 */
object PhoneStage : HamstrCast.Stage {
    private lateinit var app: Context
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    @Volatile private var loadedAt = 0L

    private val _downloading = MutableStateFlow(false)
    val downloading: StateFlow<Boolean> = _downloading.asStateFlow()

    private val _downloaded = MutableStateFlow<Set<String>>(emptySet())
    val downloaded: StateFlow<Set<String>> = _downloaded.asStateFlow()

    fun install(context: Context) {
        app = context.applicationContext
        HamstrCast.onSkip = { next ->
            main {
                val c = Playback.controller.value?.takeIf { it.isConnected } ?: return@main false
                if (next) {
                    if (!c.hasNextMediaItem()) return@main false
                    c.seekToNextMediaItem()
                    true
                } else {
                    if (c.currentPosition > 3_000L) {
                        c.seekTo(0L)
                        true
                    } else if (c.hasPreviousMediaItem()) {
                        c.seekToPreviousMediaItem()
                        true
                    } else {
                        c.seekTo(0L)
                        true
                    }
                }
            } == true
        }
        HamstrCast.install(this)
    }

    private fun <T> main(block: suspend () -> T): T? =
        runBlocking { withTimeoutOrNull(10_000) { withContext(Dispatchers.Main.immediate) { block() } } }

    override fun load(item: HamstrCast.Incoming) {
        val name = File(item.name)
        val task = DownloadTask(
            id = item.key, url = item.url, title = item.title, thumbnail = item.cover, source = item.from.name,
            durationSec = item.duration.toInt(), choice = Choice(if (item.video) Mode.Video else Mode.Audio, null, "", "", false),
            // Not a file here: its name only tells the player what kind it is.
            filePath = File(app.cacheDir, "remote/${name.name}").path, uploader = item.artist,
        )
        val cover = item.cover?.let { c -> if (c.startsWith("https://")) Uri.parse(c) else Uri.fromFile(File(c)) }
        val media = MediaItem.Builder()
            .setMediaId(item.key)
            .setUri(item.url)
            .apply { item.mime.takeIf { it.startsWith("audio/") || it.startsWith("video/") }?.let(::setMimeType) }
            .setMediaMetadata(MediaMetadata.Builder().setTitle(item.title).setArtist(item.artist ?: item.from.name).setArtworkUri(cover).build())
            .build()
        loadedAt = System.currentTimeMillis()
        main { Playback.openStream(app, task, media, (item.at * 1000).toLong(), item.play) }
    }

    override fun play() { main { Playback.controller.value?.play() } }
    override fun pause() { main { Playback.controller.value?.pause() } }
    override fun seek(seconds: Double) { main { Playback.controller.value?.seekTo((seconds * 1000).toLong()) } }

    fun next() {
        val item = HamstrCast.incoming.value ?: return
        scope.launch { HamstrCast.skip(item, next = true) }
    }

    fun previous() {
        val item = HamstrCast.incoming.value ?: return
        scope.launch {
            val pos = withContext(Dispatchers.Main.immediate) { Playback.controller.value?.currentPosition ?: 0L }
            if (pos > 3_000L) {
                withContext(Dispatchers.Main.immediate) { Playback.controller.value?.seekTo(0L) }
                return@launch
            }
            if (!HamstrCast.skip(item, next = false)) {
                withContext(Dispatchers.Main.immediate) { Playback.controller.value?.seekTo(0L) }
            }
        }
    }

    suspend fun download(context: Context): Boolean = withContext(Dispatchers.IO) {
        val item = HamstrCast.incoming.value ?: return@withContext false
        if (_downloading.value || item.key in _downloaded.value) return@withContext false
        _downloading.value = true
        try {
            val received = HamstrCast.download(item, File(context.cacheDir, "nearby-inbox")) ?: return@withContext false
            val kept = Importer.adopt(context, listOf(received), item.from.name)
            if (kept.isNotEmpty()) _downloaded.update { it + item.key }
            kept.isNotEmpty()
        } finally {
            _downloading.value = false
        }
    }

    override fun setVolume(level: Float) {
        val audio = app.getSystemService(AudioManager::class.java) ?: return
        val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        runCatching { audio.setStreamVolume(AudioManager.STREAM_MUSIC, (level * max).roundToInt().coerceIn(0, max), 0) }
    }

    override fun volume(): Float? {
        val audio = app.getSystemService(AudioManager::class.java) ?: return null
        val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC).takeIf { it > 0 } ?: return null
        return audio.getStreamVolume(AudioManager.STREAM_MUSIC) / max.toFloat()
    }

    override fun status(item: HamstrCast.Incoming): CastStatus? {
        val volume = volume()
        return main {
            val c = Playback.controller.value?.takeIf { it.isConnected }
            if (c == null || c.currentMediaItem?.mediaId != item.key) {
                // Just sent and not in the player yet.
                return@main if (System.currentTimeMillis() - loadedAt < 4_000) CastStatus(CastStatus.State.Loading, item.at, volume = volume) else null
            }
            val state = when {
                c.playerError != null -> CastStatus.State.Failed
                c.playbackState == Player.STATE_ENDED -> CastStatus.State.Finished
                c.playbackState == Player.STATE_BUFFERING || c.playbackState == Player.STATE_IDLE -> CastStatus.State.Loading
                c.playWhenReady -> CastStatus.State.Playing
                else -> CastStatus.State.Paused
            }
            CastStatus(state, c.currentPosition / 1000.0, if (c.duration == C.TIME_UNSET) item.duration else c.duration / 1000.0, volume)
        }
    }

    override fun stop(item: HamstrCast.Incoming) {
        main { if (Playback.current.value?.id == item.key) Playback.close() }
    }
}
