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
import androidx.compose.runtime.mutableFloatStateOf
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
import app.hamstr.data.DownloadTask
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
 * phone's, and holding the bar marks an A-B loop the same way.
 */
@Composable
fun DesktopPlayer(t: DownloadTask, onClose: () -> Unit) {
    val c = Hamstr.colors
    val prepared by key(t.id, t.filePath) {
        produceState<FfPlayer?>(null) {
            val file = t.filePath?.let(::File) ?: return@produceState
            value = runInterruptible(Dispatchers.IO) { FfPlayer(file, Positions.resumeAt(t.id) / 1000.0) }
        }
    }
    val player = prepared ?: return
    DisposableEffect(player) {
        player.play()
        onDispose {
            player.info?.let { Positions.save(t.id, (player.position.value * 1000).toLong(), (it.duration * 1000).toLong()) }
            player.close()
        }
    }
    val frame by player.frame.collectAsState()
    val position by player.position.collectAsState()
    val playing by player.playing.collectAsState()
    val ended by player.ended.collectAsState()
    val error by player.error.collectAsState()
    val duration = player.info?.duration ?: 0.0
    var volume by remember { mutableFloatStateOf(1f) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    LaunchedEffect(ended) { if (ended) Positions.clear(t.id) }
    var loop by remember(t.id) { mutableStateOf<AbLoop?>(null) }
    val setLoop = { l: AbLoop? -> loop = l; if (l?.b != null) player.seek(l.a / 1000.0) }
    LaunchedEffect(loop) {
        val l = loop ?: return@LaunchedEffect
        player.position.collect { at -> l.jumpFrom((at * 1000).toLong()).takeIf { it >= 0 }?.let { player.seek(it / 1000.0) } }
    }
    val keys = Modifier
        .focusRequester(focus)
        .focusable()
        .onPreviewKeyEvent { e ->
            if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
            when (e.key) {
                Key.Spacebar, Key.K -> { player.toggle(); true }
                Key.DirectionLeft, Key.J -> { player.seek(position - 10); true }
                Key.DirectionRight, Key.L -> { player.seek(position + 10); true }
                Key.Escape -> { onClose(); true }
                else -> false
            }
        }
    val bar = @Composable {
        SeekBar(if (duration > 0) (position / duration).toFloat() else 0f, loop, (duration * 1000).toLong(), setLoop) { f -> player.seek(f * duration) }
    }
    val times = @Composable {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Txt("${clock(position)} / ${clock(duration)}", style = Hamstr.type.mono, color = c.muted)
            Spacer(Modifier.width(10.dp))
            LoopPill(loop, { setLoop(null) })
        }
    }
    val buttons = @Composable {
        PlayerButton(Res.drawable.ic_replay_10, stringResource(Res.string.player_back_10)) { player.seek(position - 10) }
        PlayerButton(if (playing) Res.drawable.ic_pause else Res.drawable.ic_play_arrow, stringResource(if (playing) Res.string.player_pause else Res.string.player_play), big = true) { player.toggle() }
        PlayerButton(Res.drawable.ic_forward_10, stringResource(Res.string.player_forward_10)) { player.seek(position + 10) }
    }
    val sound = @Composable { Volume(volume) { v -> volume = v; player.setVolume(v) } }
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
                        .pointerInput(Unit) { detectTapGestures(onTap = { player.toggle() }, onDoubleTap = { offset -> player.seek(position + if (offset.x < size.width / 2) -10 else 10) }) },
                    contentAlignment = Alignment.Center,
                ) {
                    frame?.let { Image(it, null, Modifier.fillMaxSize(), contentScale = ContentScale.Fit) }
                    error?.let { Txt(it, style = Hamstr.type.small, color = Color.White, modifier = Modifier.align(Alignment.BottomCenter).padding(12.dp)) }
                }
                Row(Modifier.padding(start = 4.dp, top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
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
                        t.source?.let { Badge(it) }
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
    BoxWithConstraints(
        Modifier
            .fillMaxWidth()
            .height(22.dp)
            .pointerInput(Unit) {
                detectTapGestures { onSeek((it.x / size.width).coerceIn(0f, 1f)) }
            }
            .pointerInput(Unit) {
                detectDragGestures(
                    onDragStart = { dragging = (it.x / size.width).coerceIn(0f, 1f) },
                    onDragEnd = { dragging?.let(onSeek); dragging = null },
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
