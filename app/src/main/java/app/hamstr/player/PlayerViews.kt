package app.hamstr.player

import app.hamstr.res.*
import androidx.compose.ui.geometry.Offset
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import app.hamstr.ui.components.Specs
import app.hamstr.ui.components.rubberBand
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
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
import kotlin.math.abs
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
    onDownloadRemote: (() -> Unit)? = null,
    remoteDownloaded: Boolean = false,
) {
    val context = LocalContext.current
    val c = Hamstr.colors
    val haptics = LocalHapticFeedback.current
    // Double taps skip 10 s on the side they land on; more taps there add up (20 s, 30 s).
    var seek by remember { mutableIntStateOf(0) }
    var seekTap by remember { mutableIntStateOf(0) }
    var overlay by remember { mutableStateOf(true) }
    LaunchedEffect(seekTap) { if (seek != 0) { delay(700); seek = 0 } }
    LaunchedEffect(overlay, immersive, ui.isPlaying) { if (immersive && overlay && ui.isPlaying) { delay(2600); overlay = false } }
    // Fullscreen (or a sideways phone) follows a drag down and leaves past the threshold.
    val scope = rememberCoroutineScope()
    val pull = remember { Animatable(0f) }
    Box(
        Modifier
            .fillMaxSize()
            .pointerInput(expanded, immersive) {
                fun skip(back: Boolean) {
                    val player = controller ?: return
                    haptics.performHapticFeedback(HapticFeedbackType.ContextClick)
                    if (back) player.seekBack() else player.seekForward()
                    seek = if (back) (if (seek < 0) seek - 10 else -10) else (if (seek > 0) seek + 10 else 10)
                    seekTap++
                }
                detectTapGestures(
                    onTap = { pos ->
                        val back = pos.x < size.width / 2
                        when {
                            !expanded -> onTapMini()
                            // Right after a skip, a single tap on the same side skips again.
                            seek != 0 && back == (seek < 0) -> skip(back)
                            immersive -> overlay = !overlay
                        }
                    },
                    onDoubleTap = { pos -> if (expanded) skip(pos.x < size.width / 2) },
                )
            }
            .then(if (immersive) Modifier.pointerInput(forced) {
                val arm = 72.dp.toPx()
                var armed = false
                var travel = 0f
                detectVerticalDragGestures(
                    onDragStart = { armed = false; travel = 0f },
                    onVerticalDrag = { change, dy ->
                        change.consume()
                        travel += dy
                        val next = rubberBand(travel.coerceAtLeast(0f), arm * 1.5f)
                        if ((next > arm) != armed) {
                            armed = !armed
                            haptics.performHapticFeedback(if (armed) HapticFeedbackType.GestureThresholdActivate else HapticFeedbackType.SegmentTick)
                        }
                        scope.launch { pull.snapTo(next) }
                    },
                    onDragEnd = {
                        if (armed) {
                            haptics.performHapticFeedback(HapticFeedbackType.Confirm)
                            if (forced) onExitFullscreen() else onMinimize()
                        }
                        scope.launch { pull.animateTo(0f, Specs.settle()) }
                    },
                    onDragCancel = { scope.launch { pull.animateTo(0f, Specs.settle()) } },
                )
            } else Modifier),
        contentAlignment = Alignment.Center,
    ) {
        if (controller != null) {
            Box(
                Modifier.fillMaxSize().graphicsLayer {
                    translationY = pull.value * 0.5f
                    val s = 1f - pull.value / size.height * 0.4f
                    scaleX = s; scaleY = s
                },
                contentAlignment = Alignment.Center,
            ) {
                val casting by app.hamstr.cast.Cast.session.collectAsState()
                val on = casting
                if (on == null) PlayerSurface(controller, Modifier.aspectFit(ui.aspect), SURFACE_TYPE_TEXTURE_VIEW)
                else {
                    val preparing by on.preparing.collectAsState()
                    app.hamstr.ui.CastingCard(on.device, Modifier.fillMaxSize().background(Color.Black), preparing)
                }
            }
        }
        if (seek != 0) {
            // Beside the middle, where the play button doesn't cover it.
            Box(
                Modifier
                    .align(if (seek < 0) Alignment.CenterStart else Alignment.CenterEnd)
                    .padding(horizontal = 40.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(Color.Black.copy(alpha = 0.55f))
                    .padding(horizontal = 14.dp, vertical = 8.dp),
            ) {
                Txt(if (seek < 0) "« ${-seek} s" else "$seek s »", style = Hamstr.type.mono, color = Color.White)
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
                        if (onDownloadRemote != null) {
                            IconButton(
                                if (remoteDownloaded) Res.drawable.ic_check else Res.drawable.ic_download,
                                stringResource(if (remoteDownloaded) R.string.import_done_one else R.string.share_stash),
                                onDownloadRemote,
                                tint = if (remoteDownloaded) c.accent else Color.White,
                                background = none,
                            )
                        } else {
                            IconButton(Res.drawable.ic_open_in_new, stringResource(R.string.menu_open_with), { Playback.openWith(context, t) }, tint = Color.White, background = none)
                            IconButton(Res.drawable.ic_share, stringResource(R.string.menu_share_file), onShare, tint = Color.White, background = none)
                            IconButton(Res.drawable.ic_more_vert, stringResource(R.string.player_more), onMenu, tint = Color.White, background = none)
                        }
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

/**
 * Audio cover: the thumbnail; tap it and it dissolves into ASCII (and back).
 * Swiping left or right on the expanded cover skips to the next or previous song,
 * sliding the neighbour's cover in beside it.
 */
@Composable
internal fun Cover(
    t: DownloadTask,
    ev: Float,
    previous: DownloadTask? = null,
    next: DownloadTask? = null,
    onPrevious: (() -> Unit)? = null,
    onNext: (() -> Unit)? = null,
) {
    val bitmap = rememberImage(t.thumbnail, 720)
    val prevBitmap = rememberImage(previous?.thumbnail, 720)
    val nextBitmap = rememberImage(next?.thumbnail, 720)
    val reveal = remember(t.id) { Animatable(1f) }
    val swipeX = remember(t.id) { Animatable(0f) }
    val scope = rememberCoroutineScope()
    val haptics = LocalHapticFeedback.current
    val prevCallback by rememberUpdatedState(onPrevious)
    val nextCallback by rememberUpdatedState(onNext)

    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            .clipToBounds()
            .pointerInput(ev > 0.98f, t.id) {
                detectTapGestures {
                    if (ev > 0.98f) scope.launch { reveal.animateTo(if (reveal.targetValue > 0.5f) 0f else 1f, tween(700)) } else Playback.expand()
                }
            }
            .then(
                if (ev > 0.98f) Modifier.pointerInput(t.id) {
                    var total = 0f
                    var armed = false
                    val tracker = VelocityTracker()
                    fun arm(now: Boolean) {
                        if (now != armed) {
                            armed = now
                            haptics.performHapticFeedback(if (now) HapticFeedbackType.GestureThresholdActivate else HapticFeedbackType.SegmentTick)
                        }
                    }
                    detectHorizontalDragGestures(
                        onDragStart = { total = 0f; armed = false; tracker.resetTracking() },
                        onHorizontalDrag = { change, dx ->
                            change.consume()
                            total += dx
                            tracker.addPosition(change.uptimeMillis, Offset(total, 0f))
                            val w = size.width.toFloat().coerceAtLeast(1f)
                            val can = if (total < 0f) nextCallback != null else prevCallback != null
                            val nextX = if (can) rubberBand(total, w * 0.9f, 0.6f) else total * 0.12f
                            arm(can && abs(nextX) > w * 0.25f)
                            scope.launch { swipeX.snapTo(nextX) }
                        },
                        onDragEnd = {
                            val w = size.width.toFloat().coerceAtLeast(1f)
                            val vx = tracker.calculateVelocity().x
                            val goNext = nextCallback
                            val goPrev = prevCallback
                            when {
                                goNext != null && (swipeX.value < -w * 0.25f || (vx < -1200f && swipeX.value < 0f)) -> {
                                    haptics.performHapticFeedback(HapticFeedbackType.Confirm)
                                    scope.launch {
                                        swipeX.animateTo(-w, Specs.leave(200))
                                        goNext()
                                        swipeX.snapTo(0f)
                                    }
                                }
                                goPrev != null && (swipeX.value > w * 0.25f || (vx > 1200f && swipeX.value > 0f)) -> {
                                    haptics.performHapticFeedback(HapticFeedbackType.Confirm)
                                    scope.launch {
                                        swipeX.animateTo(w, Specs.leave(200))
                                        goPrev()
                                        swipeX.snapTo(0f)
                                    }
                                }
                                else -> scope.launch { swipeX.animateTo(0f, Specs.settle()) }
                            }
                        },
                        onDragCancel = { scope.launch { swipeX.animateTo(0f, Specs.settle()) } },
                    )
                } else Modifier
            ),
    ) {
        val w = constraints.maxWidth.toFloat()
        AsciiImage(
            bitmap = bitmap,
            reveal = reveal.value,
            cell = 7.dp,
            modifier = Modifier
                .fillMaxSize()
                .offset { IntOffset(swipeX.value.roundToInt(), 0) },
        )
        if (swipeX.value < 0f && next != null) {
            AsciiImage(
                bitmap = nextBitmap,
                reveal = 1f,
                cell = 7.dp,
                modifier = Modifier
                    .fillMaxSize()
                    .offset { IntOffset((w + swipeX.value).roundToInt(), 0) },
            )
        }
        if (swipeX.value > 0f && previous != null) {
            AsciiImage(
                bitmap = prevBitmap,
                reveal = 1f,
                cell = 7.dp,
                modifier = Modifier
                    .fillMaxSize()
                    .offset { IntOffset((swipeX.value - w).roundToInt(), 0) },
            )
        }
    }
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
