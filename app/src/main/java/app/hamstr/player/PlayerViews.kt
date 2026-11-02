package app.hamstr.player

import app.hamstr.res.*
import androidx.compose.ui.geometry.Offset
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.ui.graphics.Brush
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.media3.common.Player
import androidx.media3.ui.compose.PlayerSurface
import androidx.media3.ui.compose.SURFACE_TYPE_TEXTURE_VIEW
import app.hamstr.R
import app.hamstr.data.DownloadTask
import app.hamstr.ui.components.AsciiImage
import app.hamstr.ui.components.Badge
import app.hamstr.ui.components.Icon
import app.hamstr.ui.components.IconButton
import app.hamstr.ui.components.Txt
import app.hamstr.ui.formatBadge
import app.hamstr.ui.rememberImage
import app.hamstr.ui.theme.Hamstr
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

@Composable
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
internal fun VideoSurface(
    controller: Player?,
    ui: PlayerUi,
    t: DownloadTask,
    expanded: Boolean,
    immersive: Boolean,
    forced: Boolean,
    onExitFullscreen: () -> Unit,
    onTapMini: () -> Unit,
    onMinimize: () -> Unit,
    onMenu: () -> Unit,
    onShare: () -> Unit,
    onQuality: (() -> Unit)?,
) {
    val context = LocalContext.current
    val c = Hamstr.colors
    val haptics = LocalHapticFeedback.current
    var seekLabel by remember { mutableStateOf<String?>(null) }
    var overlay by remember { mutableStateOf(true) }
    LaunchedEffect(seekLabel) { if (seekLabel != null) { delay(650); seekLabel = null } }
    LaunchedEffect(overlay, immersive, ui.isPlaying) { if (immersive && overlay && ui.isPlaying) { delay(2600); overlay = false } }
    Box(
        Modifier
            .fillMaxSize()
            .pointerInput(expanded, immersive) {
                detectTapGestures(
                    onTap = { if (!expanded) onTapMini() else if (immersive) overlay = !overlay },
                    onDoubleTap = { pos ->
                        if (!expanded || controller == null) return@detectTapGestures
                        haptics.performHapticFeedback(HapticFeedbackType.ContextClick)
                        if (pos.x < size.width / 2) { controller.seekBack(); seekLabel = "« 10 s" } else { controller.seekForward(); seekLabel = "10 s »" }
                    },
                )
            },
        contentAlignment = Alignment.Center,
    ) {
        if (controller != null) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                PlayerSurface(controller, Modifier.aspectFit(ui.aspect), SURFACE_TYPE_TEXTURE_VIEW)
            }
        }
        seekLabel?.let {
            Box(Modifier.clip(RoundedCornerShape(12.dp)).background(Color.Black.copy(alpha = 0.55f)).padding(horizontal = 14.dp, vertical = 8.dp)) {
                Txt(it, style = Hamstr.type.mono, color = Color.White)
            }
        }
        if (immersive && controller != null) {
            val a by animateFloatAsState(if (overlay) 1f else 0f, tween(200), label = "overlay")
            // As in a video app held sideways: what plays top left, actions top right,
            // play in the middle and the timeline along the bottom, over soft scrims.
            if (a > 0f) Box(
                Modifier
                    .fillMaxSize()
                    .graphicsLayer { alpha = a }
                    .background(Brush.verticalGradient(
                        0f to Color.Black.copy(alpha = 0.72f), 0.3f to Color.Black.copy(alpha = 0.18f),
                        0.65f to Color.Black.copy(alpha = 0.18f), 1f to Color.Black.copy(alpha = 0.78f),
                    ))
                    .windowInsetsPadding(WindowInsets.displayCutout),
            ) {
                Row(
                    Modifier.align(Alignment.TopStart).fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(Res.drawable.ic_expand_more, stringResource(R.string.player_minimize), onMinimize, tint = Color.White, background = Color.Transparent)
                    Spacer(Modifier.width(4.dp))
                    Column(Modifier.weight(1f)) {
                        Txt(t.title, style = Hamstr.type.bodyMedium, color = Color.White, maxLines = 1)
                        Spacer(Modifier.height(4.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                            t.source?.let { Badge(it) }
                            if (onQuality != null) Row(
                                Modifier.clip(RoundedCornerShape(8.dp)).clickable(onClick = onQuality).padding(start = 2.dp, end = 4.dp, top = 2.dp, bottom = 2.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Badge(formatBadge(t), accent = true)
                                Icon(Res.drawable.ic_expand_more, tint = c.accent, size = 16.dp)
                            } else Badge(formatBadge(t))
                        }
                    }
                    // One pill for the actions, so they read as a group rather than loose buttons.
                    Row(Modifier.clip(RoundedCornerShape(16.dp)).background(Color.Black.copy(alpha = 0.35f))) {
                        val none = Color.Transparent
                        IconButton(Res.drawable.ic_open_in_new, stringResource(R.string.menu_open_with), { Playback.openWith(context, t) }, tint = Color.White, background = none)
                        IconButton(Res.drawable.ic_share, stringResource(R.string.menu_share_file), onShare, tint = Color.White, background = none)
                        IconButton(Res.drawable.ic_more_vert, stringResource(R.string.player_more), onMenu, tint = Color.White, background = none)
                        if (forced) IconButton(Res.drawable.ic_fullscreen_exit, stringResource(R.string.player_exit_fullscreen), onExitFullscreen, tint = Color.White, background = none)
                    }
                }
                Row(Modifier.align(Alignment.Center), horizontalArrangement = Arrangement.spacedBy(28.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(Res.drawable.ic_replay_10, stringResource(R.string.player_back_10), { controller.seekBack() }, size = 48.dp, tint = Color.White, background = Color.Transparent)
                    PlayPause(ui.isPlaying, size = 64.dp, onClick = { if (controller.isPlaying) controller.pause() else controller.play() })
                    IconButton(Res.drawable.ic_forward_10, stringResource(R.string.player_forward_10), { controller.seekForward() }, size = 48.dp, tint = Color.White, background = Color.Transparent)
                }
                Column(Modifier.align(Alignment.BottomCenter).padding(horizontal = 28.dp, vertical = 14.dp)) {
                    Scrubber(controller, ui)
                }
            }
        }
    }
}

/** Keeps a surface at the video's aspect ratio inside whatever box it gets. */
internal fun Modifier.aspectFit(aspect: Float): Modifier = this.layout { measurable, constraints ->
        val maxW = constraints.maxWidth
        val maxH = constraints.maxHeight
        var w = maxW
        var h = (w / aspect).roundToInt()
        if (h > maxH) { h = maxH; w = (h * aspect).roundToInt() }
        val p = measurable.measure(androidx.compose.ui.unit.Constraints.fixed(max(w, 1), max(h, 1)))
        layout(p.width, p.height) { p.place(0, 0) }
    }

/** Audio cover: the thumbnail; tap it and it dissolves into ASCII (and back). */
@Composable
internal fun Cover(t: DownloadTask, ev: Float) {
    val bitmap = rememberImage(t.thumbnail, 720)
    val reveal = remember { Animatable(1f) }
    val scope = rememberCoroutineScope()
    AsciiImage(
        bitmap = bitmap,
        reveal = reveal.value,
        cell = 7.dp,
        modifier = Modifier
            .fillMaxSize()
            .pointerInput(ev > 0.98f) {
                detectTapGestures {
                    if (ev > 0.98f) scope.launch { reveal.animateTo(if (reveal.targetValue > 0.5f) 0f else 1f, tween(700)) } else Playback.expand()
                }
            },
    )
}

/** Image viewer with pinch-zoom and pan. */
@Composable
internal fun ImageView(file: File?, zoomable: Boolean) {
    val bitmap = remember(file) { file?.let { runCatching { android.graphics.BitmapFactory.decodeFile(it.absolutePath) }.getOrNull() } }
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(androidx.compose.ui.geometry.Offset.Zero) }
    val state = rememberTransformableState { zoom, pan, _ ->
        scale = (scale * zoom).coerceIn(1f, 6f)
        offset = if (scale == 1f) androidx.compose.ui.geometry.Offset.Zero else offset + pan
    }
    val image = remember(bitmap) { bitmap?.asImageBitmap() }
    Canvas(
        Modifier
            .fillMaxSize()
            .then(if (zoomable) Modifier.transformable(state) else Modifier.clickable { Playback.expand() })
            .graphicsLayer { scaleX = scale; scaleY = scale; translationX = offset.x; translationY = offset.y },
    ) {
        val img = image ?: return@Canvas
        val s = min(size.width / img.width, size.height / img.height)
        val w = img.width * s
        val h = img.height * s
        drawImage(img, dstOffset = IntOffset(((size.width - w) / 2).roundToInt(), ((size.height - h) / 2).roundToInt()), dstSize = androidx.compose.ui.unit.IntSize(w.roundToInt(), h.roundToInt()))
    }
}
