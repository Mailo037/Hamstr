package app.hamstr.desktop

import app.hamstr.cast.CastStatus
import app.hamstr.cast.HamstrCast
import app.hamstr.data.Choice
import app.hamstr.data.DownloadTask
import app.hamstr.data.Mode
import app.hamstr.data.Prefs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.getAndUpdate
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File

/**
 * What another Hamstr plays on this computer ("Play on"): the stream goes into an [FfPlayer] of
 * its own, which the window shows as the player while it plays, with a task made up for it.
 * Closing it, or playing something here, hands it back to the sender.
 */
object DesktopStage : HamstrCast.Stage {
    class Playing(val item: HamstrCast.Incoming, val player: FfPlayer, val task: DownloadTask)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _now = MutableStateFlow<Playing?>(null)
    val now: StateFlow<Playing?> = _now.asStateFlow()

    private val _expanded = MutableStateFlow(true)
    val expanded: StateFlow<Boolean> = _expanded.asStateFlow()

    private val _downloading = MutableStateFlow<Float?>(null)
    val downloading: StateFlow<Float?> = _downloading.asStateFlow()

    private val _downloaded = MutableStateFlow<Set<String>>(emptySet())
    val downloaded: StateFlow<Set<String>> = _downloaded.asStateFlow()

    init {
        HamstrCast.onSkip = { next -> if (next) DesktopPlayback.next() else DesktopPlayback.previous() }
    }

    fun expand() { _expanded.value = true }
    fun minimize() { _expanded.value = false }

    override fun load(item: HamstrCast.Incoming) {
        val prev = _now.value
        val name = File(item.name)
        // A video's sound alone comes with a video's name: the kind it was sent as counts.
        val shown = if (!item.video && app.hamstr.player.kindOf(name) == app.hamstr.player.Kind.Video) File("${name.nameWithoutExtension}.m4a") else name
        val knownInfo = if (!item.video && item.duration > 0) FfPlayer.Info(item.duration, 0, 0, audio = true, video = false) else null
        val player = FfPlayer(shown, item.at, source = item.url, knownInfo = knownInfo)
        prev?.player?.loudness?.value?.let(player::setVolume)
        if (item.play) player.play()
        val task = DownloadTask(
            id = item.key, url = item.url, title = item.title, thumbnail = item.cover, source = item.from.name,
            durationSec = (player.info?.duration ?: item.duration).toInt(),
            choice = Choice(if (item.video) Mode.Video else Mode.Audio, null, "", "", false),
            uploader = item.artist,
        )
        if (prev == null || prev.item.session != item.session) _expanded.value = true
        _now.getAndUpdate { Playing(item, player, task) }?.player?.close()
    }

    private fun current() = _now.value?.takeIf { it.item == HamstrCast.incoming.value }?.player

    override fun play() { current()?.play() }
    override fun pause() { current()?.pause() }
    override fun seek(seconds: Double) { current()?.seek(seconds) }
    override fun setVolume(level: Float) { _now.value?.player?.setVolume(level) }
    override fun volume(): Float? = _now.value?.player?.loudness?.value ?: Prefs.settings.value.desktopVolume

    fun next() {
        val item = _now.value?.item ?: return
        scope.launch { HamstrCast.skip(item, next = true) }
    }

    fun previous() {
        val p = _now.value ?: return
        if (p.player.position.value > 3.0) {
            p.player.seek(0.0)
            return
        }
        scope.launch {
            if (!HamstrCast.skip(p.item, next = false)) p.player.seek(0.0)
        }
    }

    fun download() {
        val item = _now.value?.item ?: return
        if (_downloading.value != null || item.key in _downloaded.value) return
        _downloading.value = 0f
        scope.launch {
            try {
                val received = HamstrCast.download(item, File(Paths.data, "nearby-inbox")) { _downloading.value = it } ?: return@launch
                val kept = Files.adopt(listOf(received), item.from.name)
                if (kept.isNotEmpty()) _downloaded.update { it + item.key }
            } finally {
                _downloading.value = null
            }
        }
    }

    override fun status(item: HamstrCast.Incoming): CastStatus? {
        val p = _now.value?.takeIf { it.item == item }?.player ?: return null
        val state = when {
            p.error.value != null -> CastStatus.State.Failed
            p.ended.value -> CastStatus.State.Finished
            p.playing.value -> CastStatus.State.Playing
            else -> CastStatus.State.Paused
        }
        return CastStatus(state, p.position.value, p.info?.duration ?: item.duration, p.loudness.value)
    }

    override fun stop(item: HamstrCast.Incoming) {
        val p = _now.value?.takeIf { it.item == item } ?: return
        if (_now.compareAndSet(p, null)) p.player.close()
    }

    /** The user closed it here, or plays something else: it ends here and the sender takes over. */
    fun close() {
        val p = _now.getAndUpdate { null } ?: return
        p.player.close()
        HamstrCast.release(p.item)
    }
}
