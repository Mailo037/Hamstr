package app.hamstr.desktop

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import app.hamstr.ui.components.LocalReduceMotion
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.min
import app.hamstr.cast.Cast
import app.hamstr.cast.CastMedia
import app.hamstr.cast.CastStatus
import app.hamstr.data.DownloadStore
import app.hamstr.data.DownloadTask
import app.hamstr.data.Positions
import app.hamstr.data.Status
import app.hamstr.player.AbLoop
import app.hamstr.player.Kind
import app.hamstr.player.kindOf
import app.hamstr.res.*
import app.hamstr.ui.NowPlaying
import app.hamstr.ui.components.AsciiImage
import app.hamstr.ui.components.Badge
import app.hamstr.ui.components.Icon
import app.hamstr.ui.components.IconButton
import app.hamstr.ui.components.LoopBand
import app.hamstr.ui.components.LoopPill
import app.hamstr.ui.components.Specs
import app.hamstr.ui.components.Tip
import app.hamstr.ui.components.Txt
import app.hamstr.ui.components.hoverTip
import app.hamstr.ui.components.iconSwap
import app.hamstr.ui.components.loopHold
import app.hamstr.ui.components.pressable
import app.hamstr.ui.components.rememberLoopHold
import app.hamstr.ui.components.rememberTip
import app.hamstr.ui.rememberImage
import app.hamstr.ui.theme.Hamstr
import app.hamstr.ui.theme.Radii
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import org.jetbrains.compose.resources.DrawableResource
import org.jetbrains.compose.resources.stringResource
import java.io.File

/**
 * Holds what plays on the desktop and its queue (a playlist's items, or all stashed songs when
 * one song is started), so minimizing the modal into the bottom-left mini player keeps the
 * music playing and skipping between songs is immediate.
 */
object DesktopPlayback {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var openJob: Job? = null

    private val _current = MutableStateFlow<DownloadTask?>(null)
    val current: StateFlow<DownloadTask?> = _current.asStateFlow()

    private val _queue = MutableStateFlow<List<DownloadTask>>(emptyList())
    val queue: StateFlow<List<DownloadTask>> = _queue.asStateFlow()

    private val _player = MutableStateFlow<FfPlayer?>(null)
    val player: StateFlow<FfPlayer?> = _player.asStateFlow()

    private val _expanded = MutableStateFlow(true)
    val expanded: StateFlow<Boolean> = _expanded.asStateFlow()

    fun queueFor(item: DownloadTask, from: List<DownloadTask>): List<DownloadTask> {
        val ready = from.filter { it.status == Status.Done && !it.hidden && playable(it) }
        return when {
            item.group != null -> ready.filter { it.group == item.group }
            kindOf(item) == Kind.Audio -> ready.filter { kindOf(it) == Kind.Audio }
            else -> listOf(item)
        }.ifEmpty { listOf(item) }
    }

    @Synchronized
    fun open(task: DownloadTask, from: List<DownloadTask> = DownloadStore.tasks.value, expand: Boolean = true) {
        DesktopStage.close()
        _queue.value = queueFor(task, from)
        if (expand) _expanded.value = true
        start(task)
    }

    @Synchronized
    private fun start(task: DownloadTask) {
        saveAndCloseCurrent()
        _current.value = task
        NowPlaying.id.value = task.id
        openJob?.cancel()
        val file = task.filePath?.let(::File) ?: return
        val at = Positions.resumeAt(task.id) / 1000.0
        val known = if (kindOf(task) == Kind.Audio && task.durationSec > 0) {
            FfPlayer.Info(task.durationSec.toDouble(), 0, 0, audio = true, video = false)
        } else null
        openJob = scope.launch {
            val p = FfPlayer(file, at, knownInfo = known)
            var keep = false
            synchronized(this@DesktopPlayback) {
                if (_current.value?.id == task.id) {
                    _player.value = p
                    keep = true
                }
            }
            if (!keep) {
                p.close()
                return@launch
            }
            val cast = Cast.session.value
            if (cast != null) {
                val video = p.info?.video == true
                val dur = p.info?.duration ?: task.durationSec.toDouble()
                cast.load(CastMedia(task.id, file, task.title, task.uploader ?: task.source, castMime(file, video), video, task.thumbnail, dur), at, true)
            } else {
                p.play()
            }
        }
    }

    fun hasNext(): Boolean {
        val cur = _current.value ?: return false
        val q = _queue.value
        val i = q.indexOfFirst { it.id == cur.id }
        return i in 0 until q.lastIndex
    }

    fun hasPrevious(): Boolean {
        val cur = _current.value ?: return false
        val q = _queue.value
        val i = q.indexOfFirst { it.id == cur.id }
        return i > 0
    }

    @Synchronized
    fun next(): Boolean {
        val cur = _current.value ?: return false
        val q = _queue.value
        val i = q.indexOfFirst { it.id == cur.id }
        if (i !in 0 until q.lastIndex) return false
        start(q[i + 1])
        return true
    }

    @Synchronized
    fun previous(): Boolean {
        val cur = _current.value ?: return false
        val p = _player.value
        val s = Cast.session.value
        val there = s != null && s.media.value?.id == cur.id
        val pos = if (there) s!!.status.value.positionNow() else p?.position?.value ?: 0.0
        if (pos > 3.0) {
            if (there) s!!.seek(0.0) else p?.seek(0.0)
            return true
        }
        val q = _queue.value
        val i = q.indexOfFirst { it.id == cur.id }
        if (i > 0) {
            start(q[i - 1])
            return true
        }
        if (there) s!!.seek(0.0) else p?.seek(0.0)
        return true
    }

    fun expand() { _expanded.value = true }
    fun minimize() { _expanded.value = false }

    @Synchronized
    fun close() {
        openJob?.cancel()
        openJob = null
        saveAndCloseCurrent()
        _current.value = null
        _queue.value = emptyList()
        NowPlaying.id.value = null
    }

    private fun saveAndCloseCurrent() {
        val t = _current.value
        val p = _player.value
        _player.value = null
        if (t != null && p != null) {
            val s = Cast.session.value
            val at = if (s != null && s.media.value?.id == t.id) s.status.value.positionNow().also { s.pause() } else p.position.value
            p.info?.let { Positions.save(t.id, (at * 1000).toLong(), (it.duration * 1000).toLong()) }
        }
        p?.close()
    }
}

/**
 * Monitors the active [DesktopPlayback] or [Cast] session regardless of whether the full modal
 * or the mini player is shown, so tracks advance in the background and cast handoffs stay live.
 */
@Composable
fun DesktopPlaybackEffects() {
    val task by DesktopPlayback.current.collectAsState()
    val player by DesktopPlayback.player.collectAsState()
    val t = task ?: return
    val p = player ?: return
    val remote by Cast.session.collectAsState()
    val castMedia by (remote?.media ?: remember { MutableStateFlow<CastMedia?>(null) }).collectAsState()
    val castStatus by (remote?.status ?: remember { MutableStateFlow(CastStatus()) }).collectAsState()
    val there = remote != null && castMedia?.id == t.id
    var lastThere by remember(t.id) { mutableStateOf<Double?>(null) }

    LaunchedEffect(remote, t.id, p) {
        if (remote != null) {
            val at = p.position.value
            p.pause()
            val file = t.filePath?.let(::File)
            if (remote!!.media.value?.id != t.id && file != null) {
                val video = p.info?.video == true
                val dur = p.info?.duration ?: t.durationSec.toDouble()
                remote!!.load(CastMedia(t.id, file, t.title, t.uploader ?: t.source, castMime(file, video), video, t.thumbnail, dur), at, true)
            }
        } else {
            lastThere?.let { at -> p.seek(at); lastThere = null }
        }
    }
    LaunchedEffect(there, castStatus) {
        if (there) lastThere = castStatus.positionNow()
    }
    val localEnded by p.ended.collectAsState()
    val ended = if (there) castStatus.state == CastStatus.State.Finished else localEnded
    LaunchedEffect(ended, t.id) {
        if (ended) {
            Positions.clear(t.id)
            if (DesktopPlayback.hasNext()) DesktopPlayback.next()
        }
    }
}

/**
 * The desktop's player for a stashed video or song ([FfPlayer]), as a modal. A video takes as
 * much of the window as fits while it keeps its shape, with the bar and the buttons under it;
 * a song is a card, the cover beside its title, the bar and the buttons. Space, ← and → work,
 * Esc minimizes into the bottom-left mini player. It carries on where the file was left and
 * remembers where it stops, like the phone's, and holding the bar marks an A-B loop the same
 * way. With [stream] it plays what another Hamstr sends here ([DesktopStage]), which owns that
 * player.
 */
@Composable
fun DesktopPlayer(
    t: DownloadTask,
    onCast: () -> Unit = {},
    stream: FfPlayer? = null,
    onMinimize: () -> Unit = {},
    onClose: () -> Unit,
    // A video over the whole screen ([WindowFrame.fullscreen]), its controls over scrims.
    fullscreen: Boolean = false,
    onFullscreen: (() -> Unit)? = null,
) {
    val c = Hamstr.colors
    val managedPlayer by DesktopPlayback.player.collectAsState()
    val managedTask by DesktopPlayback.current.collectAsState()
    val ownsPlayer = stream == null && managedTask?.id != t.id
    val fallback by key(t.id, t.filePath, stream, ownsPlayer) {
        produceState(stream ?: if (!ownsPlayer) managedPlayer else null, managedPlayer) {
            if (stream != null) { value = stream; return@produceState }
            if (!ownsPlayer) { value = managedPlayer; return@produceState }
            val file = t.filePath?.let(::File) ?: return@produceState
            value = runInterruptible(Dispatchers.IO) { FfPlayer(file, Positions.resumeAt(t.id) / 1000.0) }
        }
    }
    val player = stream ?: (if (!ownsPlayer) managedPlayer else fallback) ?: return
    // Playing on a TV or speaker: the file goes there and this player is its remote.
    val remote by Cast.session.collectAsState()
    val cast = remote.takeIf { stream == null }
    val castStatus by (cast?.status ?: remember { MutableStateFlow(CastStatus()) }).collectAsState()
    val castMedia by (cast?.media ?: remember { MutableStateFlow<CastMedia?>(null) }).collectAsState()
    val there = cast != null && castMedia?.id == t.id
    // While it plays there, the clock moves on between the device's reports.
    val tick by produceState(0L, there, castStatus) {
        while (there && castStatus.state == CastStatus.State.Playing) { value = System.currentTimeMillis(); kotlinx.coroutines.delay(500) }
    }
    DisposableEffect(player, ownsPlayer) {
        if (!ownsPlayer) return@DisposableEffect onDispose { }
        if (Cast.session.value == null) player.play()
        onDispose {
            val s = Cast.session.value
            val at = if (s != null && s.media.value?.id == t.id) s.status.value.positionNow().also { s.pause() } else player.position.value
            player.info?.let { Positions.save(t.id, (at * 1000).toLong(), (it.duration * 1000).toLong()) }
            player.close()
        }
    }
    val frame by player.frame.collectAsState()
    val localPosition by player.position.collectAsState()
    val localPlaying by player.playing.collectAsState()
    val error by player.error.collectAsState()
    val position = if (there) { tick; castStatus.positionNow() } else localPosition
    val playing = if (there) castStatus.state == CastStatus.State.Playing else localPlaying
    val duration = castStatus.duration.takeIf { there && it > 0 } ?: player.info?.duration?.takeIf { it > 0 } ?: t.durationSec.toDouble()
    val localVolume by player.loudness.collectAsState()
    val volume = if (there) castStatus.volume ?: 1f else localVolume
    val toggle = { if (there) cast!!.toggle() else player.toggle() }
    val seek = { s: Double -> if (there) cast!!.seek(s.coerceIn(0.0, duration.takeIf { it > 0 } ?: Double.MAX_VALUE)) else player.seek(s) }
    val seekNow by rememberUpdatedState(seek)
    val toggleNow by rememberUpdatedState(toggle)
    val positionNow by rememberUpdatedState(position)
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    var loop by remember(t.id) { mutableStateOf<AbLoop?>(null) }
    val setLoop = { l: AbLoop? -> loop = l; if (l?.b != null) seek(l.a / 1000.0) }
    LaunchedEffect(loop, there, player) {
        val l = loop ?: return@LaunchedEffect
        if (there) return@LaunchedEffect
        player.position.collect { at -> l.jumpFrom((at * 1000).toLong()).takeIf { it >= 0 }?.let { player.seek(it / 1000.0) } }
    }
    val info = player.info
    val queue by DesktopPlayback.queue.collectAsState()
    val isAudio = info?.video != true && kindOf(t) != Kind.Video
    val useTrackSkip = isAudio || queue.size > 1
    val onPrevTrack = { if (stream != null) DesktopStage.previous() else { DesktopPlayback.previous(); Unit } }
    val onNextTrack = { if (stream != null) DesktopStage.next() else { DesktopPlayback.next(); Unit } }
    val setVolume = { v: Float -> val l = v.coerceIn(0f, 1f); if (there) cast!!.setVolume(l) else player.setVolume(l) }
    var beforeMute by remember { mutableStateOf<Float?>(null) }
    val canFullscreen = onFullscreen != null && info?.video == true
    val onFullscreenNow by rememberUpdatedState(onFullscreen.takeIf { canFullscreen })
    // A song took the video's place: the window comes back from fullscreen.
    LaunchedEffect(fullscreen, info) { if (fullscreen && info != null && !info.video) onFullscreen?.invoke() }

    val keys = Modifier
        .focusRequester(focus)
        .focusable()
        .onPreviewKeyEvent { e ->
            if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
            when (e.key) {
                Key.Spacebar, Key.K -> { toggle(); true }
                Key.DirectionLeft, Key.J -> { if (e.isShiftPressed && useTrackSkip) onPrevTrack() else seek(position - 10); true }
                Key.DirectionRight, Key.L -> { if (e.isShiftPressed && useTrackSkip) onNextTrack() else seek(position + 10); true }
                Key.DirectionUp -> { beforeMute = null; setVolume(volume + 0.05f); true }
                Key.DirectionDown -> { setVolume(volume - 0.05f); true }
                Key.M -> { if (volume > 0f) { beforeMute = volume; setVolume(0f) } else { setVolume(beforeMute ?: 1f); beforeMute = null }; true }
                Key.F -> onFullscreenNow?.let { it(); true } ?: false
                Key.N -> if (useTrackSkip) { onNextTrack(); true } else false
                Key.P -> if (useTrackSkip) { onPrevTrack(); true } else false
                // Out of fullscreen first, then down into the mini player.
                Key.Escape -> { if (fullscreen) onFullscreen?.invoke() else onMinimize(); true }
                else -> false
            }
        }
    val bar = @Composable {
        SeekBar(if (duration > 0) (position / duration).toFloat() else 0f, loop, (duration * 1000).toLong(), setLoop) { f -> seek(f * duration) }
    }
    val times = @Composable {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Txt("${clock(position)} / ${clock(duration)}", style = Hamstr.type.mono, color = c.muted)
            Spacer(Modifier.width(10.dp))
            LoopPill(loop, { setLoop(null) })
        }
    }
    val buttons = @Composable {
        if (useTrackSkip) {
            PlayerButton(Res.drawable.ic_skip_previous, stringResource(Res.string.player_previous), onClick = onPrevTrack)
            PlayerButton(if (playing) Res.drawable.ic_pause else Res.drawable.ic_play_arrow, stringResource(if (playing) Res.string.player_pause else Res.string.player_play), big = true) { toggle() }
            PlayerButton(Res.drawable.ic_skip_next, stringResource(Res.string.player_next), onClick = onNextTrack)
        } else {
            PlayerButton(Res.drawable.ic_replay_10, stringResource(Res.string.player_back_10)) { seek(position - 10) }
            PlayerButton(if (playing) Res.drawable.ic_pause else Res.drawable.ic_play_arrow, stringResource(if (playing) Res.string.player_pause else Res.string.player_play), big = true) { toggle() }
            PlayerButton(Res.drawable.ic_forward_10, stringResource(Res.string.player_forward_10)) { seek(position + 10) }
        }
    }
    val downloading by DesktopStage.downloading.collectAsState()
    val downloaded by DesktopStage.downloaded.collectAsState()
    val sound = @Composable {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (stream == null) {
                CastButton(cast != null, onCast)
                Spacer(Modifier.width(6.dp))
            } else {
                StreamDownloadButton(
                    done = t.id in downloaded,
                    busy = downloading != null,
                    onClick = DesktopStage::download,
                )
                Spacer(Modifier.width(6.dp))
            }
            Volume(volume) { v -> if (there) cast!!.setVolume(v) else player.setVolume(v) }
        }
    }
    val topButtons = @Composable {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (canFullscreen) {
                IconButton(
                    if (fullscreen) Res.drawable.ic_fullscreen_exit else Res.drawable.ic_fullscreen,
                    stringResource(if (fullscreen) Res.string.player_exit_fullscreen else Res.string.player_fullscreen),
                    { onFullscreen?.invoke() }, tint = c.muted, background = Color.Transparent, size = 34.dp,
                )
            }
            if (!fullscreen) IconButton(Res.drawable.ic_expand_more, stringResource(Res.string.player_minimize), onMinimize, tint = c.muted, background = Color.Transparent, size = 34.dp)
            IconButton(Res.drawable.ic_close, stringResource(Res.string.player_close), onClose, tint = c.muted, background = Color.Transparent, size = 34.dp)
        }
    }

    if (info?.video == true && fullscreen) {
        // The picture fills the screen; the controls come with the pointer and go 2.5 s after it
        // rests, over scrims, while the video plays. Paused, they stay.
        var moved by remember { mutableStateOf(0L) }
        var shown by remember { mutableStateOf(true) }
        LaunchedEffect(moved, playing) {
            shown = true
            if (playing) { kotlinx.coroutines.delay(2_500); shown = false }
        }
        val reduce = LocalReduceMotion.current
        val chrome by animateFloatAsState(if (shown) 1f else 0f, if (reduce) snap() else tween(if (shown) 160 else 420), label = "fullscreenChrome")
        Box(
            Modifier
                .fillMaxSize()
                .background(Color.Black)
                .then(keys)
                .pointerInput(Unit) {
                    awaitPointerEventScope {
                        while (true) {
                            val ev = awaitPointerEvent(PointerEventPass.Initial)
                            if (ev.type == PointerEventType.Move || ev.type == PointerEventType.Press) moved = System.nanoTime()
                        }
                    }
                }
                .pointerHoverIcon(if (shown) PointerIcon.Default else BlankPointer)
                .pointerInput(there) { detectTapGestures(onTap = { toggleNow() }, onDoubleTap = { onFullscreenNow?.invoke() }) },
            contentAlignment = Alignment.Center,
        ) {
            if (cast != null) {
                val preparing by cast.preparing.collectAsState()
                app.hamstr.ui.CastingCard(cast.device, preparing = preparing)
            } else frame?.let { Image(it, null, Modifier.fillMaxSize(), contentScale = ContentScale.Fit) }
            if (chrome > 0.01f) {
                Row(
                    Modifier
                        .align(Alignment.TopCenter)
                        .fillMaxWidth()
                        .graphicsLayer { alpha = chrome }
                        .background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.72f), Color.Transparent)))
                        .padding(start = 28.dp, end = 16.dp, top = 16.dp, bottom = 40.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Txt(t.title, style = Hamstr.type.section, color = Color.White, maxLines = 1, modifier = Modifier.weight(1f))
                    topButtons()
                }
                Column(
                    Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .graphicsLayer { alpha = chrome }
                        .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.78f))))
                        .padding(start = 28.dp, end = 28.dp, top = 48.dp, bottom = 20.dp),
                ) {
                    error?.let { Txt(it, style = Hamstr.type.small, color = Color.White, modifier = Modifier.padding(bottom = 8.dp)) }
                    bar()
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.weight(1f)) { times() }
                        buttons()
                        Box(Modifier.weight(1f), contentAlignment = Alignment.CenterEnd) { sound() }
                    }
                }
            }
        }
    } else if (info?.video == true) {
        // As big as the window allows, in the video's own shape, with room for the controls.
        BoxWithConstraints {
            val ratio = (if (info.width > 0 && info.height > 0) info.width.toFloat() / info.height else 16f / 9f).coerceIn(0.5f, 3f)
            val width = min(maxWidth - 24.dp, (maxHeight - VIDEO_CHROME) * ratio).coerceAtLeast(280.dp)
            Column(
                Modifier
                    .width(width + 24.dp)
                    .clip(RoundedCornerShape(Radii.sheet))
                    .background(c.overlay)
                    .clickable(remember { MutableInteractionSource() }, null) {}
                    .then(keys)
                    .padding(12.dp),
            ) {
                Box(
                    Modifier.fillMaxWidth().aspectRatio(ratio).clip(RoundedCornerShape(Radii.row)).background(Color.Black)
                        // A click plays or pauses, a double click fills the screen (J and L skip 10 s).
                        .pointerInput(there) { detectTapGestures(onTap = { toggleNow() }, onDoubleTap = { onFullscreenNow?.invoke() }) },
                    contentAlignment = Alignment.Center,
                ) {
                    if (cast != null) {
                        val preparing by cast.preparing.collectAsState()
                        app.hamstr.ui.CastingCard(cast.device, preparing = preparing)
                    }
                    else frame?.let { Image(it, null, Modifier.fillMaxSize(), contentScale = ContentScale.Fit) }
                    error?.let { Txt(it, style = Hamstr.type.small, color = Color.White, modifier = Modifier.align(Alignment.BottomCenter).padding(12.dp)) }
                }
                Row(Modifier.padding(start = 4.dp, top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (stream != null) t.source?.let { Badge(stringResource(Res.string.cast_from, it)); Spacer(Modifier.width(8.dp)) }
                    Txt(t.title, style = Hamstr.type.bodyMedium, maxLines = 1, modifier = Modifier.weight(1f))
                    topButtons()
                }
                Box(Modifier.padding(horizontal = 4.dp)) { bar() }
                Row(Modifier.padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.weight(1f)) { times() }
                    buttons()
                    Box(Modifier.weight(1f), contentAlignment = Alignment.CenterEnd) { sound() }
                }
            }
        }
    } else {
        // A song: the cover beside what plays and how to steer it.
        var ascii by remember(t.id) { mutableStateOf(false) }
        val reveal by animateFloatAsState(if (ascii) 0f else 1f, Specs.blend(), label = "coverAscii")
        Row(
            Modifier
                .widthIn(max = 660.dp)
                .fillMaxWidth()
                .clip(RoundedCornerShape(Radii.sheet))
                .background(c.overlay)
                .clickable(remember { MutableInteractionSource() }, null) {}
                .then(keys)
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val cover = rememberImage(t.thumbnail, 480)
            Box(
                Modifier
                    .size(COVER)
                    .clip(RoundedCornerShape(Radii.row))
                    .background(c.black)
                    .clickable(remember { MutableInteractionSource() }, null) { ascii = !ascii },
                contentAlignment = Alignment.Center,
            ) {
                if (cover != null) AsciiImage(cover, reveal = reveal, cell = 4.dp, modifier = Modifier.fillMaxSize())
                else Icon(Res.drawable.ic_music_note, tint = c.accent, size = 56.dp)
            }
            Spacer(Modifier.width(20.dp))
            Column(Modifier.weight(1f).height(COVER)) {
                Row(verticalAlignment = Alignment.Top) {
                    Column(Modifier.weight(1f).padding(top = 6.dp)) {
                        t.source?.let { Badge(if (stream != null) stringResource(Res.string.cast_from, it) else it) }
                        Spacer(Modifier.height(6.dp))
                        Txt(t.title, style = Hamstr.type.section, maxLines = 2)
                        (t.uploader ?: t.source)?.let {
                            Spacer(Modifier.height(2.dp))
                            Txt(it, style = Hamstr.type.small, color = c.muted, maxLines = 1)
                        }
                    }
                    topButtons()
                }
                Spacer(Modifier.weight(1f))
                error?.let { Txt(it, style = Hamstr.type.small, color = c.red, maxLines = 2, modifier = Modifier.padding(bottom = 6.dp)) }
                bar()
                times()
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    buttons()
                    Spacer(Modifier.weight(1f))
                    sound()
                }
            }
        }
    }
}

/**
 * The compact player card at the foot of the left sidebar column (bottom-left), matching the
 * phone's landscape mini player. Clicking it opens the full player modal again; its buttons
 * steer playback directly without leaving the current screen.
 */
@Composable
fun DesktopMiniPlayer() {
    val staged by DesktopStage.now.collectAsState()
    val localTask by DesktopPlayback.current.collectAsState()
    val localPlayer by DesktopPlayback.player.collectAsState()
    val queue by DesktopPlayback.queue.collectAsState()
    val t = staged?.task ?: localTask ?: return
    val player = staged?.player ?: localPlayer ?: return
    val isStream = staged != null
    val c = Hamstr.colors

    val remote by Cast.session.collectAsState()
    val cast = remote.takeIf { !isStream }
    val castStatus by (cast?.status ?: remember { MutableStateFlow(CastStatus()) }).collectAsState()
    val castMedia by (cast?.media ?: remember { MutableStateFlow<CastMedia?>(null) }).collectAsState()
    val there = cast != null && castMedia?.id == t.id
    val tick by produceState(0L, there, castStatus) {
        while (there && castStatus.state == CastStatus.State.Playing) { value = System.currentTimeMillis(); kotlinx.coroutines.delay(500) }
    }
    val localPosition by player.position.collectAsState()
    val localPlaying by player.playing.collectAsState()
    val position = if (there) { tick; castStatus.positionNow() } else localPosition
    val playing = if (there) castStatus.state == CastStatus.State.Playing else localPlaying
    val duration = castStatus.duration.takeIf { there && it > 0 } ?: player.info?.duration?.takeIf { it > 0 } ?: t.durationSec.toDouble()
    val progress = if (duration > 0) (position / duration).toFloat().coerceIn(0f, 1f) else 0f

    val info = player.info
    val isAudio = info?.video != true && kindOf(t) != Kind.Video
    val useTrackSkip = isAudio || queue.size > 1
    val toggle = { if (there) cast!!.toggle() else player.toggle() }
    val seek = { s: Double -> if (there) cast!!.seek(s.coerceIn(0.0, duration.takeIf { it > 0 } ?: Double.MAX_VALUE)) else player.seek(s) }
    val onPrev = { if (isStream) DesktopStage.previous() else { DesktopPlayback.previous(); Unit } }
    val onNext = { if (isStream) DesktopStage.next() else { DesktopPlayback.next(); Unit } }
    val onExpand = { if (isStream) DesktopStage.expand() else DesktopPlayback.expand() }
    val onClose = { if (isStream) DesktopStage.close() else DesktopPlayback.close() }

    val downloading by DesktopStage.downloading.collectAsState()
    val downloaded by DesktopStage.downloaded.collectAsState()

    Column(
        Modifier
            .width(216.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(c.overlay)
            // The wheel over the mini player turns the volume.
            .wheel { up -> PlayingNow.volumeBy(if (up) 0.05f else -0.05f) }
            .clickable(onClick = onExpand),
    ) {
        BoxWithConstraints(Modifier.fillMaxWidth().height(3.dp).background(c.line)) {
            Box(Modifier.width(maxWidth * progress).height(3.dp).background(c.accent))
        }
        Column(Modifier.padding(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                val cover = rememberImage(t.thumbnail, 160)
                Box(
                    Modifier.size(42.dp).clip(RoundedCornerShape(10.dp)).background(c.black),
                    contentAlignment = Alignment.Center,
                ) {
                    if (cover != null) AsciiImage(cover, reveal = 1f, cell = 3.dp, modifier = Modifier.fillMaxSize())
                    else Icon(if (isAudio) Res.drawable.ic_music_note else Res.drawable.ic_movie, tint = c.accent, size = 20.dp)
                }
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Txt(t.title, style = Hamstr.type.bodyMedium, maxLines = 1)
                    Txt(
                        (t.uploader ?: t.source) ?: "${clock(position)} / ${clock(duration)}",
                        style = Hamstr.type.small,
                        color = c.muted,
                        maxLines = 1,
                    )
                }
                IconButton(
                    Res.drawable.ic_close,
                    stringResource(Res.string.player_close),
                    onClose,
                    tint = c.muted,
                    background = Color.Transparent,
                    size = 28.dp,
                )
            }
            Spacer(Modifier.height(8.dp))
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (useTrackSkip) {
                    MiniControlButton(Res.drawable.ic_skip_previous, stringResource(Res.string.player_previous), onClick = onPrev)
                    MiniControlButton(
                        if (playing) Res.drawable.ic_pause else Res.drawable.ic_play_arrow,
                        stringResource(if (playing) Res.string.player_pause else Res.string.player_play),
                        big = true,
                        onClick = toggle,
                    )
                    MiniControlButton(Res.drawable.ic_skip_next, stringResource(Res.string.player_next), onClick = onNext)
                } else {
                    MiniControlButton(Res.drawable.ic_replay_10, stringResource(Res.string.player_back_10)) { seek(position - 10) }
                    MiniControlButton(
                        if (playing) Res.drawable.ic_pause else Res.drawable.ic_play_arrow,
                        stringResource(if (playing) Res.string.player_pause else Res.string.player_play),
                        big = true,
                        onClick = toggle,
                    )
                    MiniControlButton(Res.drawable.ic_forward_10, stringResource(Res.string.player_forward_10)) { seek(position + 10) }
                }
                if (isStream) {
                    Spacer(Modifier.width(4.dp))
                    StreamDownloadButton(
                        done = t.id in downloaded,
                        busy = downloading != null,
                        size = 32.dp,
                        onClick = DesktopStage::download,
                    )
                }
            }
        }
    }
}

@Composable
private fun MiniControlButton(icon: DrawableResource, label: String, big: Boolean = false, onClick: () -> Unit) {
    val c = Hamstr.colors
    val tip = rememberTip()
    Box(
        Modifier
            .padding(horizontal = 3.dp)
            .size(if (big) 36.dp else 32.dp)
            .clip(RoundedCornerShape(50))
            .background(if (big) c.accent else c.surface)
            .hoverTip(tip)
            .pressable(onClick, label = label, pressedScale = if (big) 0.9f else 0.92f),
        contentAlignment = Alignment.Center,
    ) {
        AnimatedContent(icon, transitionSpec = { iconSwap() }, label = "miniPlayerIcon") { i ->
            Icon(i, tint = if (big) c.onAccent else c.ink, size = if (big) 20.dp else 17.dp)
        }
        Tip(tip, label)
    }
}

/** Downloads what another Hamstr plays here into this computer's stash. */
@Composable
private fun StreamDownloadButton(done: Boolean, busy: Boolean, size: Dp = 34.dp, onClick: () -> Unit) {
    val c = Hamstr.colors
    val tip = rememberTip()
    val label = stringResource(
        when {
            done -> Res.string.import_done_one
            busy -> Res.string.stash_downloading
            else -> Res.string.share_stash
        }
    )
    Box(Modifier.hoverTip(tip)) {
        IconButton(
            if (done) Res.drawable.ic_check else Res.drawable.ic_download,
            label,
            onClick = { if (!done && !busy) onClick() },
            tint = if (done || busy) c.accent else c.muted,
            background = Color.Transparent,
            size = size,
        )
        Tip(tip, label)
    }
}

/** The cast button: accent while something plays on a TV or speaker. */
@Composable
private fun CastButton(on: Boolean, onClick: () -> Unit) {
    val c = Hamstr.colors
    val tip = rememberTip()
    Box(Modifier.hoverTip(tip)) {
        IconButton(Res.drawable.ic_cast, stringResource(Res.string.cast_button), onClick, tint = if (on) c.accent else c.muted, background = Color.Transparent, size = 34.dp)
        Tip(tip, stringResource(Res.string.cast_button))
    }
}

/** What a TV is told the file is; the sound of a video file goes as sound. */
internal fun castMime(file: File, video: Boolean): String {
    val m = app.hamstr.player.mimeOf(file)
    return if (!video && m.startsWith("video/")) m.replace("video/", "audio/") else m
}

// What a video's modal needs besides the picture: padding, the title row, the bar and the buttons.
private val VIDEO_CHROME = 150.dp
private val COVER = 220.dp

/** How loud, on a short bar; its tooltip says what it is, and the wheel over it steps by 5 %. */
@Composable
private fun Volume(volume: Float, onChange: (Float) -> Unit) {
    val tip = rememberTip()
    val now by rememberUpdatedState(volume)
    val change by rememberUpdatedState(onChange)
    Box(Modifier.width(96.dp).hoverTip(tip).wheel { up -> change((now + if (up) 0.05f else -0.05f).coerceIn(0f, 1f)) }) {
        SeekBar(volume, onSeek = onChange)
        Tip(tip, stringResource(Res.string.desktop_volume))
    }
}

/** The mouse wheel over this: [onStep] with true for up (away from you), once per notch. */
internal fun Modifier.wheel(onStep: (Boolean) -> Unit) = pointerInput(Unit) {
    awaitPointerEventScope {
        while (true) {
            val ev = awaitPointerEvent()
            if (ev.type != PointerEventType.Scroll) continue
            val dy = ev.changes.sumOf { it.scrollDelta.y.toDouble() }
            if (dy == 0.0) continue
            onStep(dy < 0)
            ev.changes.forEach { it.consume() }
        }
    }
}

/** No pointer over a fullscreen video whose controls have gone. */
private val BlankPointer by lazy {
    PointerIcon(java.awt.Toolkit.getDefaultToolkit().createCustomCursor(java.awt.image.BufferedImage(1, 1, java.awt.image.BufferedImage.TYPE_INT_ARGB), java.awt.Point(0, 0), "none"))
}

/** A thin bar: click or drag anywhere on it to go there. With [onLoop], holding it marks an A-B loop. */
@Composable
private fun SeekBar(fraction: Float, loop: AbLoop? = null, durationMs: Long = 0, onLoop: ((AbLoop?) -> Unit)? = null, onSeek: (Float) -> Unit) {
    val c = Hamstr.colors
    var dragging by remember { mutableStateOf<Float?>(null) }
    val hold = rememberLoopHold()
    val fractionNow by rememberUpdatedState(fraction)
    val onSeekNow by rememberUpdatedState(onSeek)
    // Thin at rest; under the pointer or while dragged it thickens and shows its thumb.
    val source = remember { MutableInteractionSource() }
    val hovered by source.collectIsHoveredAsState()
    val lift by animateFloatAsState(if (hovered || dragging != null) 1f else 0f, if (LocalReduceMotion.current) snap() else tween(120), label = "seekLift")
    BoxWithConstraints(
        Modifier
            .fillMaxWidth()
            .height(22.dp)
            .hoverable(source)
            .pointerInput(Unit) {
                detectTapGestures { onSeekNow((it.x / size.width).coerceIn(0f, 1f)) }
            }
            .pointerInput(Unit) {
                detectDragGestures(
                    onDragStart = { dragging = (it.x / size.width).coerceIn(0f, 1f) },
                    onDragEnd = { dragging?.let(onSeekNow); dragging = null },
                    onDragCancel = { dragging = null },
                ) { change, _ -> dragging = (change.position.x / size.width).coerceIn(0f, 1f) }
            }
            .then(if (onLoop != null) Modifier.loopHold(hold, loop, { durationMs }, { (fractionNow * durationMs).toLong() }, onLoop) else Modifier),
        contentAlignment = Alignment.CenterStart,
    ) {
        if (onLoop != null) LoopBand(hold.shown(loop, durationMs), durationMs, Modifier.matchParentSize())
        val shown = (dragging ?: fraction).coerceIn(0f, 1f)
        val track = 4.dp + 2.dp * lift
        Box(Modifier.fillMaxWidth().height(track).clip(RoundedCornerShape(3.dp)).background(c.line))
        Box(Modifier.width(maxWidth * shown).height(track).clip(RoundedCornerShape(3.dp)).background(c.accent))
        Box(
            Modifier.padding(start = (maxWidth * shown - 6.dp).coerceAtLeast(0.dp)).size(12.dp)
                .graphicsLayer { alpha = lift; scaleX = 0.5f + 0.5f * lift; scaleY = 0.5f + 0.5f * lift }
                .clip(RoundedCornerShape(6.dp)).background(c.accent),
        )
    }
}

@Composable
private fun PlayerButton(icon: DrawableResource, label: String, big: Boolean = false, onClick: () -> Unit) {
    val c = Hamstr.colors
    val tip = rememberTip()
    Box(
        Modifier.padding(horizontal = 4.dp).size(if (big) 48.dp else 38.dp).clip(RoundedCornerShape(50)).background(if (big) c.accent else c.surface)
            .hoverTip(tip).pressable(onClick, label = label, pressedScale = if (big) 0.9f else 0.92f),
        contentAlignment = Alignment.Center,
    ) {
        AnimatedContent(icon, transitionSpec = { iconSwap() }, label = "playerIcon") { i ->
            Icon(i, tint = if (big) c.onAccent else c.ink, size = if (big) 26.dp else 20.dp)
        }
        Tip(tip, label)
    }
}

private fun clock(seconds: Double): String {
    val s = seconds.toLong().coerceAtLeast(0)
    return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, s / 60 % 60, s % 60) else "%d:%02d".format(s / 60, s % 60)
}

/** What the built-in player takes; anything else opens in the system's own app. */
fun playable(t: DownloadTask): Boolean {
    val f = t.filePath?.let(::File)?.takeIf { it.isFile } ?: return false
    return app.hamstr.player.kindOf(t).let { it == app.hamstr.player.Kind.Video || it == app.hamstr.player.Kind.Audio } && Tools.ffmpeg.isFile && f.length() > 0
}
