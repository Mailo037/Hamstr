package app.hamstr.desktop

import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.min
import app.hamstr.cast.Cast
import app.hamstr.cast.CastMedia
import app.hamstr.cast.CastStatus
import app.hamstr.data.DownloadTask
import kotlinx.coroutines.flow.MutableStateFlow
import app.hamstr.data.Positions
import app.hamstr.player.AbLoop
import app.hamstr.res.*
import app.hamstr.ui.components.AsciiImage
import app.hamstr.ui.components.Badge
import app.hamstr.ui.components.Icon
import app.hamstr.ui.components.IconButton
import app.hamstr.ui.components.LoopBand
import app.hamstr.ui.components.LoopPill
import app.hamstr.ui.components.Tip
import app.hamstr.ui.components.hoverTip
import app.hamstr.ui.components.iconSwap
import app.hamstr.ui.components.loopHold
import app.hamstr.ui.components.rememberLoopHold
import app.hamstr.ui.components.rememberTip
import app.hamstr.ui.components.Txt
import app.hamstr.ui.components.pressable
import app.hamstr.ui.rememberImage
import app.hamstr.ui.theme.Hamstr
import app.hamstr.ui.theme.Radii
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import org.jetbrains.compose.resources.DrawableResource
import org.jetbrains.compose.resources.stringResource
import java.io.File

/**
 * The desktop's player for a stashed video or song ([FfPlayer]), as a modal. A video takes as
 * much of the window as fits while it keeps its shape, with the bar and the buttons under it;
 * a song is a card, the cover beside its title, the bar and the buttons. Space, ← and → work,
 * Esc closes. It carries on where the file was left and remembers where it stops, like the
 * phone's, and holding the bar marks an A-B loop the same way. With [stream] it plays what
 * another Hamstr sends here ([DesktopStage]), which owns that player.
 */
@Composable
fun DesktopPlayer(t: DownloadTask, onCast: () -> Unit = {}, stream: FfPlayer? = null, onClose: () -> Unit) {
    val c = Hamstr.colors
    val prepared by key(t.id, t.filePath, stream) {
        produceState(stream) {
            if (stream != null) return@produceState
            val file = t.filePath?.let(::File) ?: return@produceState
            value = runInterruptible(Dispatchers.IO) { FfPlayer(file, Positions.resumeAt(t.id) / 1000.0) }
        }
    }
    val player = prepared ?: return
    // Playing on a TV or speaker: the file goes there and this player is its remote.
    val remote by Cast.session.collectAsState()
    val cast = remote.takeIf { stream == null }
    val castStatus by (cast?.status ?: remember { MutableStateFlow(CastStatus()) }).collectAsState()
    val castMedia by (cast?.media ?: remember { MutableStateFlow<CastMedia?>(null) }).collectAsState()
    val there = cast != null && castMedia?.id == t.id
    var lastThere by remember { mutableStateOf<Double?>(null) }
    LaunchedEffect(cast) {
        if (cast != null) {
            val at = player.position.value
            player.pause()
            val file = t.filePath?.let(::File)
            if (cast.media.value?.id != t.id && file != null) {
                val video = player.info?.video == true
                cast.load(CastMedia(t.id, file, t.title, t.uploader ?: t.source, castMime(file, video), video, t.thumbnail), at, true)
            }
        } else lastThere?.let { at -> player.seek(at); lastThere = null }
    }
    // While it plays there, the clock moves on between the device's reports.
    val tick by produceState(0L, there, castStatus) {
        while (there && castStatus.state == CastStatus.State.Playing) { value = System.currentTimeMillis(); kotlinx.coroutines.delay(500) }
    }
    LaunchedEffect(there, castStatus, tick) { if (there) lastThere = castStatus.positionNow() }
    DisposableEffect(player) {
        if (stream != null) return@DisposableEffect onDispose { }
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
    val localEnded by player.ended.collectAsState()
    val error by player.error.collectAsState()
    val position = if (there) { tick; castStatus.positionNow() } else localPosition
    val playing = if (there) castStatus.state == CastStatus.State.Playing else localPlaying
    val ended = if (there) castStatus.state == CastStatus.State.Finished else localEnded
    val duration = castStatus.duration.takeIf { there && it > 0 } ?: player.info?.duration ?: 0.0
    val localVolume by player.loudness.collectAsState()
    val volume = if (there) castStatus.volume ?: 1f else localVolume
    val toggle = { if (there) cast!!.toggle() else player.toggle() }
    val seek = { s: Double -> if (there) cast!!.seek(s.coerceIn(0.0, duration.takeIf { it > 0 } ?: Double.MAX_VALUE)) else player.seek(s) }
    val seekNow by rememberUpdatedState(seek)
    val toggleNow by rememberUpdatedState(toggle)
    val positionNow by rememberUpdatedState(position)
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    LaunchedEffect(ended) { if (ended && stream == null) Positions.clear(t.id) }
    var loop by remember(t.id) { mutableStateOf<AbLoop?>(null) }
    val setLoop = { l: AbLoop? -> loop = l; if (l?.b != null) seek(l.a / 1000.0) }
    LaunchedEffect(loop, there) {
        val l = loop ?: return@LaunchedEffect
        if (there) return@LaunchedEffect
        player.position.collect { at -> l.jumpFrom((at * 1000).toLong()).takeIf { it >= 0 }?.let { player.seek(it / 1000.0) } }
    }
    val keys = Modifier
        .focusRequester(focus)
        .focusable()
        .onPreviewKeyEvent { e ->
            if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
            when (e.key) {
                Key.Spacebar, Key.K -> { toggle(); true }
                Key.DirectionLeft, Key.J -> { seek(position - 10); true }
                Key.DirectionRight, Key.L -> { seek(position + 10); true }
                Key.Escape -> { onClose(); true }
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
        PlayerButton(Res.drawable.ic_replay_10, stringResource(Res.string.player_back_10)) { seek(position - 10) }
        PlayerButton(if (playing) Res.drawable.ic_pause else Res.drawable.ic_play_arrow, stringResource(if (playing) Res.string.player_pause else Res.string.player_play), big = true) { toggle() }
        PlayerButton(Res.drawable.ic_forward_10, stringResource(Res.string.player_forward_10)) { seek(position + 10) }
    }
    val sound = @Composable {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (stream == null) {
                CastButton(cast != null, onCast)
                Spacer(Modifier.width(6.dp))
            }
            Volume(volume) { v -> if (there) cast!!.setVolume(v) else player.setVolume(v) }
        }
    }
    val close = @Composable {
        IconButton(Res.drawable.ic_close, stringResource(Res.string.player_close), onClose, tint = c.muted, background = Color.Transparent, size = 34.dp)
    }

    val info = player.info
    if (info?.video == true) {
        // As big as the window allows, in the video's own shape, with room for the controls.
        BoxWithConstraints {
            val ratio = (if (info.width > 0 && info.height > 0) info.width.toFloat() / info.height else 16f / 9f).coerceIn(0.5f, 3f)
            val width = min(maxWidth - 24.dp, (maxHeight - VIDEO_CHROME) * ratio).coerceAtLeast(280.dp)
            Column(Modifier.width(width + 24.dp).clip(RoundedCornerShape(Radii.sheet)).background(c.overlay).then(keys).padding(12.dp)) {
                Box(
                    Modifier.fillMaxWidth().aspectRatio(ratio).clip(RoundedCornerShape(Radii.row)).background(Color.Black)
                        .pointerInput(there) { detectTapGestures(onTap = { toggleNow() }, onDoubleTap = { offset -> seekNow(positionNow + if (offset.x < size.width / 2) -10 else 10) }) },
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
                    close()
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
        Row(
            Modifier.widthIn(max = 660.dp).fillMaxWidth().clip(RoundedCornerShape(Radii.sheet)).background(c.overlay).then(keys).padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val cover = rememberImage(t.thumbnail, 480)
            Box(Modifier.size(COVER).clip(RoundedCornerShape(Radii.row)).background(c.black), contentAlignment = Alignment.Center) {
                if (cover != null) AsciiImage(cover, reveal = 1f, cell = 4.dp, modifier = Modifier.fillMaxSize())
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
                    close()
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
private fun castMime(file: File, video: Boolean): String {
    val m = app.hamstr.player.mimeOf(file)
    return if (!video && m.startsWith("video/")) m.replace("video/", "audio/") else m
}

// What a video's modal needs besides the picture: padding, the title row, the bar and the buttons.
private val VIDEO_CHROME = 150.dp
private val COVER = 220.dp

/** How loud, on a short bar; its tooltip says what it is. */
@Composable
private fun Volume(volume: Float, onChange: (Float) -> Unit) {
    val tip = rememberTip()
    Box(Modifier.width(96.dp).hoverTip(tip)) {
        SeekBar(volume, onSeek = onChange)
        Tip(tip, stringResource(Res.string.desktop_volume))
    }
}

/** A thin bar: click or drag anywhere on it to go there. With [onLoop], holding it marks an A-B loop. */
@Composable
private fun SeekBar(fraction: Float, loop: AbLoop? = null, durationMs: Long = 0, onLoop: ((AbLoop?) -> Unit)? = null, onSeek: (Float) -> Unit) {
    val c = Hamstr.colors
    var dragging by remember { mutableStateOf<Float?>(null) }
    val hold = rememberLoopHold()
    val fractionNow by rememberUpdatedState(fraction)
    val onSeekNow by rememberUpdatedState(onSeek)
    BoxWithConstraints(
        Modifier
            .fillMaxWidth()
            .height(22.dp)
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
        Box(Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)).background(c.line))
        Box(Modifier.width(maxWidth * shown).height(4.dp).clip(RoundedCornerShape(2.dp)).background(c.accent))
        Box(Modifier.padding(start = (maxWidth * shown - 6.dp).coerceAtLeast(0.dp)).size(12.dp).clip(RoundedCornerShape(6.dp)).background(c.accent))
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
