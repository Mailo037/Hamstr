package app.hamstr.desktop

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
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
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
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
import app.hamstr.data.DownloadTask
import app.hamstr.data.Positions
import app.hamstr.player.AbLoop
import app.hamstr.res.*
import app.hamstr.ui.components.AsciiImage
import app.hamstr.ui.components.Icon
import app.hamstr.ui.components.LoopBand
import app.hamstr.ui.components.LoopPill
import app.hamstr.ui.components.loopHold
import app.hamstr.ui.components.rememberLoopHold
import app.hamstr.ui.components.Txt
import app.hamstr.ui.components.pressable
import app.hamstr.ui.rememberImage
import app.hamstr.ui.theme.Hamstr
import org.jetbrains.compose.resources.DrawableResource
import java.io.File

/**
 * The desktop's player for a stashed video or song ([FfPlayer]): the picture (or the cover),
 * a bar to seek on, play and ±10 s, the volume. Space, ← and → work too. It carries on where
 * the file was left and remembers where it stops, like the phone's, and holding the bar marks
 * an A-B loop the same way.
 */
@Composable
fun DesktopPlayer(t: DownloadTask, onClose: () -> Unit) {
    val c = Hamstr.colors
    val file = remember(t.id) { t.filePath?.let(::File) }
    val player = remember(t.id) { file?.let { FfPlayer(it, Positions.resumeAt(t.id) / 1000.0) } }
    DisposableEffect(player) {
        player?.play()
        onDispose {
            player?.let { p -> p.info?.let { Positions.save(t.id, (p.position.value * 1000).toLong(), (it.duration * 1000).toLong()) } }
            player?.close()
        }
    }
    if (player == null) return
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

    Column(
        Modifier
            .widthIn(max = 980.dp)
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(c.overlay)
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
            .padding(14.dp),
    ) {
        val info = player.info
        val ratio = if (info != null && info.video && info.width > 0 && info.height > 0) info.width.toFloat() / info.height else 16f / 9f
        Box(
            Modifier.fillMaxWidth().aspectRatio(ratio.coerceIn(0.5f, 3f)).clip(RoundedCornerShape(12.dp)).background(Color.Black)
                .pointerInput(Unit) { detectTapGestures(onTap = { player.toggle() }, onDoubleTap = { offset -> player.seek(position + if (offset.x < size.width / 2) -10 else 10) }) },
            contentAlignment = Alignment.Center,
        ) {
            val shown = frame
            if (info?.video == true && shown != null) {
                Image(shown, null, Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
            } else if (info?.video != true) {
                // A song: its cover in the middle, drawn the way the stash draws it.
                val cover = rememberImage(t.thumbnail, 480)
                if (cover != null) AsciiImage(cover, reveal = 1f, cell = 6.dp, modifier = Modifier.fillMaxHeight().aspectRatio(1f))
                else Icon(Res.drawable.ic_music_note, tint = c.accent, size = 64.dp)
            }
            error?.let { Txt(it, style = Hamstr.type.small, color = Color.White, modifier = Modifier.align(Alignment.BottomCenter).padding(12.dp)) }
        }
        Spacer(Modifier.height(12.dp))
        Txt(t.title, style = Hamstr.type.bodyMedium, maxLines = 1)
        Spacer(Modifier.height(8.dp))
        SeekBar(if (duration > 0) (position / duration).toFloat() else 0f, loop, (duration * 1000).toLong(), setLoop) { f -> player.seek(f * duration) }
        Spacer(Modifier.height(6.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Txt("${clock(position)} / ${clock(duration)}", style = Hamstr.type.mono, color = c.muted)
            Spacer(Modifier.width(10.dp))
            LoopPill(loop, { setLoop(null) })
            Spacer(Modifier.weight(1f))
            PlayerButton(Res.drawable.ic_replay_10) { player.seek(position - 10) }
            PlayerButton(if (playing) Res.drawable.ic_pause else Res.drawable.ic_play_arrow, big = true) { player.toggle() }
            PlayerButton(Res.drawable.ic_forward_10) { player.seek(position + 10) }
            Spacer(Modifier.weight(1f))
            Box(Modifier.width(120.dp)) { SeekBar(volume) { v -> volume = v; player.setVolume(v) } }
            Spacer(Modifier.width(8.dp))
            PlayerButton(Res.drawable.ic_close, onClick = onClose)
        }
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
private fun PlayerButton(icon: DrawableResource, big: Boolean = false, onClick: () -> Unit) {
    val c = Hamstr.colors
    Box(
        Modifier.padding(horizontal = 4.dp).size(if (big) 48.dp else 38.dp).clip(RoundedCornerShape(50)).background(if (big) c.accent else c.surface).pressable(onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, tint = if (big) c.canvas else c.ink, size = if (big) 26.dp else 20.dp)
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
