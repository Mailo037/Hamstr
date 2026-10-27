package app.hamstr.player

import android.content.pm.ActivityInfo
import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalActivity
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
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
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.lerp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.ui.compose.PlayerSurface
import androidx.media3.ui.compose.SURFACE_TYPE_TEXTURE_VIEW
import app.hamstr.R
import app.hamstr.data.DownloadTask
import app.hamstr.ui.components.AsciiImage
import app.hamstr.ui.components.Badge
import app.hamstr.ui.components.ButtonKind
import app.hamstr.ui.components.DecodeText
import app.hamstr.ui.components.HButton
import app.hamstr.ui.components.Icon
import app.hamstr.ui.components.IconButton
import app.hamstr.ui.components.Txt
import app.hamstr.ui.components.pressable
import app.hamstr.ui.components.rememberVelocityStretch
import app.hamstr.ui.components.stretchX
import app.hamstr.ui.formatBadge
import app.hamstr.ui.canHaveVersions
import app.hamstr.ui.rememberBitmap
import app.hamstr.ui.theme.Hamstr
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import androidx.compose.ui.graphics.lerp
import app.hamstr.ui.components.animateSelection

/** Live player facts the UI draws from. */
@Stable
class PlayerUi {
    var isPlaying by mutableStateOf(false)
    var position by mutableLongStateOf(0L)
    var duration by mutableLongStateOf(0L)
    var aspect by mutableFloatStateOf(16f / 9f)
    var speed by mutableFloatStateOf(1f)
    var repeat by mutableIntStateOf(Player.REPEAT_MODE_OFF)
    var shuffle by mutableStateOf(false)
    var hasNext by mutableStateOf(false)
    var hasPrevious by mutableStateOf(false)
}

@Composable
fun rememberPlayerUi(player: Player?): PlayerUi {
    val ui = remember { PlayerUi() }
    DisposableEffect(player) {
        if (player == null) return@DisposableEffect onDispose { }
        val listener = object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) { ui.isPlaying = isPlaying }
            override fun onVideoSizeChanged(videoSize: VideoSize) {
                if (videoSize.width > 0 && videoSize.height > 0) ui.aspect = videoSize.width * videoSize.pixelWidthHeightRatio / videoSize.height
            }
            override fun onMediaItemTransition(mediaItem: androidx.media3.common.MediaItem?, reason: Int) { ui.aspect = 16f / 9f }
            override fun onPlaybackParametersChanged(playbackParameters: androidx.media3.common.PlaybackParameters) { ui.speed = playbackParameters.speed }
            override fun onRepeatModeChanged(repeatMode: Int) { ui.repeat = repeatMode }
            override fun onShuffleModeEnabledChanged(shuffleModeEnabled: Boolean) { ui.shuffle = shuffleModeEnabled }
            override fun onEvents(player: Player, events: Player.Events) {
                ui.hasNext = player.hasNextMediaItem()
                ui.hasPrevious = player.hasPreviousMediaItem()
            }
        }
        player.addListener(listener)
        ui.isPlaying = player.isPlaying
        ui.speed = player.playbackParameters.speed
        ui.repeat = player.repeatMode
        ui.shuffle = player.shuffleModeEnabled
        ui.hasNext = player.hasNextMediaItem()
        ui.hasPrevious = player.hasPreviousMediaItem()
        player.videoSize.takeIf { it.width > 0 && it.height > 0 }?.let { ui.aspect = it.width * it.pixelWidthHeightRatio / it.height }
        onDispose { player.removeListener(listener) }
    }
    LaunchedEffect(player) {
        while (player != null) {
            ui.position = player.currentPosition
            ui.duration = player.duration.coerceAtLeast(0L)
            delay(200)
        }
    }
    return ui
}

private data class Box4(val x: Float, val y: Float, val w: Float, val h: Float) {
    fun towards(to: Box4, t: Float) = Box4(lerp(x, to.x, t), lerp(y, to.y, t), lerp(w, to.w, t), lerp(h, to.h, t))
}

/**
 * The player. One surface morphs between a full-screen view and a floating mini player
 * above the tab bar: swipe the big player up or down to shrink it, tap or swipe the mini
 * player up to bring it back, swipe it sideways to stop. Videos also go fullscreen
 * (landscape) and picture-in-picture when the app is left.
 */
@Composable
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
fun PlayerHost(miniBottom: Dp, inPip: Boolean, onMenu: (DownloadTask) -> Unit, onQuality: (DownloadTask) -> Unit) {
    val task by Playback.current.collectAsState()
    val controller by Playback.controller.collectAsState()
    if (inPip) {
        Box(Modifier.fillMaxSize().background(Color.Black), contentAlignment = Alignment.Center) {
            if (task?.filePath?.let { kindOf(File(it)) } == Kind.Video) {
                controller?.let { PlayerSurface(it, Modifier.fillMaxSize(), SURFACE_TYPE_TEXTURE_VIEW) }
            }
        }
        return
    }

    val t = task ?: return
    val ui = rememberPlayerUi(controller)
    val file = remember(t.id) { t.filePath?.let(::File) }
    val kind = remember(t.id) { file?.let(::kindOf) ?: Kind.Other }
    val activity = LocalActivity.current

    val c = Hamstr.colors
    val scope = rememberCoroutineScope()
    val haptics = LocalHapticFeedback.current
    val density = LocalDensity.current
    val expandedTarget by Playback.expanded.collectAsState()
    val e = remember { Animatable(0f) }
    val dismissX = remember { Animatable(0f) }
    var dragging by remember { mutableStateOf(false) }
    var fullscreen by remember { mutableStateOf(false) }
    val settle = spring<Float>(dampingRatio = 0.84f, stiffness = 360f)
    LaunchedEffect(expandedTarget, t.id) {
        dismissX.snapTo(0f)
        if (!dragging) e.animateTo(if (expandedTarget) 1f else 0f, settle)
    }
    // Fullscreen = landscape without system bars; leaving it restores both.
    DisposableEffect(fullscreen) {
        val window = activity?.window
        if (activity != null && window != null) {
            val ctl = WindowCompat.getInsetsController(window, window.decorView)
            if (fullscreen) {
                // Upright videos (Shorts, Reels, TikTok) stay upright; wide ones turn sideways.
                activity.requestedOrientation = when {
                    ui.aspect < 0.9f -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
                    ui.aspect > 1.1f -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                    else -> ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
                }
                ctl.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                ctl.hide(WindowInsetsCompat.Type.systemBars())
            } else {
                activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
                ctl.show(WindowInsetsCompat.Type.systemBars())
            }
        }
        onDispose { }
    }
    BackHandler(enabled = fullscreen || expandedTarget) {
        if (fullscreen) fullscreen = false else Playback.minimize()
    }

    fun close() = scope.launch {
        fullscreen = false
        Playback.close()
    }

    // Vertical drags on the big player shrink it (either direction); release decides.
    val shrinkDrag = Modifier.pointerInput(Unit) {
        var travel = 0f
        detectVerticalDragGestures(
            onDragStart = { dragging = true; travel = 0f },
            onVerticalDrag = { change, dy ->
                change.consume()
                travel += dy
                scope.launch { e.snapTo((1f - abs(travel) / (size.height * 0.55f)).coerceIn(0.02f, 1f)) }
            },
            onDragEnd = {
                dragging = false
                if (e.value < 0.75f) {
                    haptics.performHapticFeedback(HapticFeedbackType.GestureThresholdActivate)
                    Playback.minimize()
                    scope.launch { e.animateTo(0f, settle) }
                } else scope.launch { e.animateTo(1f, settle) }
            },
            onDragCancel = { dragging = false; scope.launch { e.animateTo(1f, settle) } },
        )
    }

    // Only the expanded player claims the whole screen; the mini player leaves the list usable.
    val expandedNow = e.value > 0.001f && !fullscreen
    BoxWithConstraints(Modifier.fillMaxSize().then(if (expandedNow) shrinkDrag else Modifier)) {
        val W = constraints.maxWidth.toFloat()
        val H = constraints.maxHeight.toFloat()
        val px = { d: Dp -> with(density) { d.toPx() } }
        val top = WindowInsets.statusBars.getTop(density).toFloat()
        val bottomBar = px(miniBottom)

        // Mini player: a 64 dp bar floating above the tab bar.
        val miniH = px(64.dp)
        val mini = Box4(px(12.dp), H - bottomBar - miniH - px(8.dp), W - px(24.dp), miniH)
        val miniMediaW = if (kind == Kind.Video) px(56.dp) * 16f / 9f else px(56.dp)
        val miniMedia = Box4(mini.x + px(4.dp), mini.y + px(4.dp), miniMediaW, px(56.dp))
        // Full view: media under a 56 dp top bar.
        val fullMedia = when {
            fullscreen -> Box4(0f, 0f, W, H)
            // Upright videos get more of the screen; the controls still fit below.
            kind == Kind.Video -> Box4(0f, top + px(56.dp), W, min(W / ui.aspect, H * if (ui.aspect < 0.9f) 0.6f else 0.55f))
            kind == Kind.Image -> Box4(0f, top + px(56.dp), W, H - top - px(56.dp) - WindowInsets.navigationBars.getBottom(density) - px(150.dp))
            else -> Box4(px(28.dp), top + px(76.dp), W - px(56.dp), W - px(56.dp))
        }
        val ev = e.value.coerceIn(0f, 1f)
        val media = miniMedia.towards(fullMedia, ev)
        val stretch by rememberVelocityStretch({ e.value * H }, sensitivity = 0.00002f, max = 0.05f)

        // ---- full view backdrop + chrome ------------------------------------------------
        if (ev > 0.001f) {
            Box(
                Modifier
                    .fillMaxSize()
                    .graphicsLayer { alpha = ev }
                    .background(if (fullscreen) Color.Black else c.canvas)
                    .pointerInput(Unit) { detectTapGestures { } }, // keep taps off the list below
            )
            if (!fullscreen) {
                // Top bar
                Row(
                    Modifier
                        .offset { IntOffset(0, (top + (1f - ev) * px(40.dp)).roundToInt()) }
                        .fillMaxWidth()
                        .height(56.dp)
                        .graphicsLayer { alpha = ((ev - 0.5f) * 2f).coerceIn(0f, 1f) }
                        .padding(horizontal = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(R.drawable.ic_expand_more, stringResource(R.string.player_minimize), { Playback.minimize() }, tint = c.ink)
                    Spacer(Modifier.width(12.dp))
                    Txt(
                        stringResource(when (kind) { Kind.Video -> R.string.player_video; Kind.Audio -> R.string.player_now_playing; Kind.Image -> R.string.player_image; Kind.Other -> R.string.player_file }),
                        style = Hamstr.type.bodyMedium, color = c.muted, modifier = Modifier.weight(1f),
                    )
                    IconButton(R.drawable.ic_open_in_new, stringResource(R.string.menu_open_with), { Playback.openWith(activity ?: return@IconButton, t) })
                    Spacer(Modifier.width(8.dp))
                    IconButton(R.drawable.ic_share, stringResource(R.string.menu_share_file), { Playback.shareFile(activity ?: return@IconButton, t) })
                    Spacer(Modifier.width(8.dp))
                    IconButton(R.drawable.ic_more_vert, stringResource(R.string.player_more), { onMenu(t) })
                }
                // Info + controls under the media
                Column(
                    Modifier
                        .offset { IntOffset(0, (fullMedia.y + fullMedia.h + px(20.dp) + (1f - ev) * px(60.dp)).roundToInt()) }
                        .fillMaxWidth()
                        .graphicsLayer { alpha = ((ev - 0.4f) / 0.6f).coerceIn(0f, 1f) }
                        .padding(horizontal = 20.dp),
                ) {
                    DecodeText(t.title, style = Hamstr.type.section, maxLines = 2)
                    Spacer(Modifier.height(6.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        t.source?.let { Badge(it) }
                        Badge(file?.extension ?: formatBadge(t))
                        // Tap the quality to switch to another one or fetch more.
                        if (canHaveVersions(t)) Row(
                            Modifier.clip(RoundedCornerShape(8.dp)).clickable { onQuality(t) }.padding(start = 2.dp, end = 4.dp, top = 2.dp, bottom = 2.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Badge(formatBadge(t), accent = true)
                            Icon(R.drawable.ic_expand_more, tint = c.accent, size = 16.dp)
                        }
                    }
                    Spacer(Modifier.height(18.dp))
                    when (kind) {
                        Kind.Video, Kind.Audio -> {
                            if (controller != null) Transport(controller!!, ui, video = kind == Kind.Video, onFullscreen = { fullscreen = true })
                        }
                        Kind.Image -> {}
                        Kind.Other -> {
                            Txt(stringResource(R.string.player_cant_show, file?.extension ?: "?"), color = c.muted)
                            Spacer(Modifier.height(14.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                HButton(stringResource(R.string.menu_open_with), { activity?.let { Playback.openWith(it, t) } }, Modifier.weight(1f), kind = ButtonKind.Primary, icon = R.drawable.ic_open_in_new)
                                HButton(stringResource(R.string.menu_share_file), { activity?.let { Playback.shareFile(it, t) } }, Modifier.weight(1f), icon = R.drawable.ic_share)
                            }
                        }
                    }
                }
            }
        }

        // ---- mini bar ------------------------------------------------------------------
        if (ev < 0.999f) {
            Box(
                Modifier
                    .offset { IntOffset((mini.x + dismissX.value).roundToInt(), (mini.y + ev * px(40.dp)).roundToInt()) }
                    .width(with(density) { mini.w.toDp() })
                    .height(64.dp)
                    .graphicsLayer {
                        alpha = (1f - ev * 2f).coerceIn(0f, 1f) * (1f - abs(dismissX.value) / W).coerceIn(0f, 1f)
                        rotationZ = dismissX.value / W * 6f
                    }
                    .clip(RoundedCornerShape(18.dp))
                    .background(c.raised)
                    .clickable(remember { MutableInteractionSource() }, null) { Playback.expand() }
                    .pointerInput(Unit) {
                        detectDragGestures(
                            onDragStart = { dragging = true },
                            onDragEnd = {
                                dragging = false
                                when {
                                    abs(dismissX.value) > W * 0.35f -> {
                                        haptics.performHapticFeedback(HapticFeedbackType.Confirm)
                                        scope.launch {
                                            dismissX.animateTo(if (dismissX.value > 0) W else -W, tween(180))
                                            close()
                                        }
                                    }
                                    e.value > 0.2f -> { Playback.expand(); scope.launch { e.animateTo(1f, settle) } }
                                    else -> scope.launch {
                                        launch { dismissX.animateTo(0f, spring(0.5f, 500f)) }
                                        e.animateTo(0f, settle)
                                    }
                                }
                            },
                            onDragCancel = { dragging = false; scope.launch { dismissX.animateTo(0f) } },
                        ) { change, drag ->
                            change.consume()
                            scope.launch {
                                if (abs(drag.x) > abs(drag.y) || dismissX.value != 0f) dismissX.snapTo(dismissX.value + drag.x)
                                else e.snapTo((e.value - drag.y / (H * 0.6f)).coerceIn(0f, 1f))
                            }
                        }
                    },
            ) {
                Row(Modifier.fillMaxSize().padding(start = with(density) { (miniMediaW + px(16.dp)).toDp() }, end = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Txt(t.title, style = Hamstr.type.bodyMedium, maxLines = 1)
                        Spacer(Modifier.height(2.dp))
                        Txt(if (ui.duration > 0) "${fmt(ui.position)} / ${fmt(ui.duration)}" else t.source ?: "", style = Hamstr.type.mono, color = c.ghost, maxLines = 1)
                    }
                    if (kind == Kind.Video || kind == Kind.Audio) {
                        PlayPause(ui.isPlaying, size = 40.dp, onClick = { controller?.let { if (it.isPlaying) it.pause() else it.play() } })
                    }
                    if (ui.hasNext) IconButton(R.drawable.ic_skip_next, stringResource(R.string.player_next), { controller?.seekToNextMediaItem() }, background = Color.Transparent)
                    IconButton(R.drawable.ic_close, stringResource(R.string.player_close), { close() }, background = Color.Transparent)
                }
                // Concentric with the bar's 18 dp corners (the media sits 4 dp in, at 14 dp);
                // the progress line stays on the straight part of the bottom edge.
                if (ui.duration > 0) {
                    Box(
                        Modifier.align(Alignment.BottomStart).padding(horizontal = 18.dp)
                            .fillMaxWidth(ui.position / ui.duration.toFloat()).height(2.dp).clip(CircleShape).background(c.accent),
                    )
                }
            }
        }

        // ---- the media itself, at the morphing rect ----------------------------------
        val m = if (ev < 0.999f) media.copy(x = media.x + dismissX.value * (1f - ev)) else media
        Box(
            Modifier
                .offset { IntOffset(m.x.roundToInt(), m.y.roundToInt()) }
                .width(with(density) { m.w.toDp() })
                .height(with(density) { m.h.toDp() })
                .graphicsLayer {
                    alpha = if (ev < 0.5f) (1f - abs(dismissX.value) / W).coerceIn(0f, 1f) else 1f
                    scaleY = 1f + abs(stretch)
                }
                .clip(RoundedCornerShape(if (fullscreen) 0.dp else lerp(14f, if (kind == Kind.Video || kind == Kind.Image) 0f else 22f, ev).dp))
                .background(if (kind == Kind.Video) Color.Black else c.black),
        ) {
            when (kind) {
                Kind.Video -> VideoSurface(controller, ui, expanded = ev > 0.98f, fullscreen = fullscreen, onExitFullscreen = { fullscreen = false }, onTapMini = { Playback.expand() }, onMenu = { fullscreen = false; onMenu(t) })
                Kind.Audio -> Cover(t, ev)
                Kind.Image -> ImageView(file, zoomable = ev > 0.98f)
                Kind.Other -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(R.drawable.ic_description, tint = c.accent, size = lerp(22f, 64f, ev).dp)
                        if (ev > 0.5f) Badge(file?.extension ?: stringResource(R.string.player_file), accent = true)
                    }
                }
            }
        }
    }
}

@Composable
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
private fun VideoSurface(controller: Player?, ui: PlayerUi, expanded: Boolean, fullscreen: Boolean, onExitFullscreen: () -> Unit, onTapMini: () -> Unit, onMenu: () -> Unit) {
    val c = Hamstr.colors
    val haptics = LocalHapticFeedback.current
    var seekLabel by remember { mutableStateOf<String?>(null) }
    var overlay by remember { mutableStateOf(true) }
    LaunchedEffect(seekLabel) { if (seekLabel != null) { delay(650); seekLabel = null } }
    LaunchedEffect(overlay, fullscreen, ui.isPlaying) { if (fullscreen && overlay && ui.isPlaying) { delay(2600); overlay = false } }
    Box(
        Modifier
            .fillMaxSize()
            .pointerInput(expanded, fullscreen) {
                detectTapGestures(
                    onTap = { if (!expanded) onTapMini() else if (fullscreen) overlay = !overlay },
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
        if (fullscreen && controller != null) {
            val a by animateFloatAsState(if (overlay) 1f else 0f, tween(200), label = "overlay")
            if (a > 0f) Box(Modifier.fillMaxSize().graphicsLayer { alpha = a }.background(Color.Black.copy(alpha = 0.35f))) {
                Row(Modifier.align(Alignment.TopEnd).padding(20.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    IconButton(R.drawable.ic_more_vert, stringResource(R.string.player_more), onMenu, tint = Color.White, background = Color.Black.copy(alpha = 0.4f))
                    IconButton(R.drawable.ic_fullscreen_exit, stringResource(R.string.player_exit_fullscreen), onExitFullscreen, tint = Color.White, background = Color.Black.copy(alpha = 0.4f))
                }
                Box(Modifier.align(Alignment.Center)) { PlayPause(ui.isPlaying, size = 64.dp, onClick = { if (controller.isPlaying) controller.pause() else controller.play() }) }
                Column(Modifier.align(Alignment.BottomCenter).padding(horizontal = 28.dp, vertical = 22.dp)) {
                    Scrubber(controller, ui)
                }
            }
        }
    }
}

/** Keeps a surface at the video's aspect ratio inside whatever box it gets. */
private fun Modifier.aspectFit(aspect: Float): Modifier = this.layout { measurable, constraints ->
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
private fun Cover(t: DownloadTask, ev: Float) {
    val bitmap = rememberBitmap(t.thumbnail, 720)
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
private fun ImageView(file: File?, zoomable: Boolean) {
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

@Composable
private fun Transport(controller: Player, ui: PlayerUi, video: Boolean, onFullscreen: () -> Unit) {
    val c = Hamstr.colors
    val haptics = LocalHapticFeedback.current
    val queue by Playback.queue.collectAsState()
    Scrubber(controller, ui)
    Spacer(Modifier.height(18.dp))
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        val speeds = listOf(1f, 1.25f, 1.5f, 2f, 0.75f)
        Box(
            Modifier.weight(1f),
            contentAlignment = Alignment.CenterStart,
        ) {
            Box(
                Modifier
                    .clip(RoundedCornerShape(10.dp))
                    .background(c.surface)
                    .pressable({
                        haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
                        val next = speeds[(speeds.indexOfFirst { abs(it - ui.speed) < 0.01f } + 1) % speeds.size]
                        controller.setPlaybackSpeed(next)
                    }, label = stringResource(R.string.player_speed))
                    .heightIn(min = 40.dp)
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                contentAlignment = Alignment.Center,
            ) { Txt("${if (ui.speed % 1f == 0f) ui.speed.toInt() else ui.speed}×", style = Hamstr.type.mono) }
        }
        // In a queue the side buttons skip between files; for one file they jump 10 s.
        if (queue.size > 1) {
            IconButton(R.drawable.ic_skip_previous, stringResource(R.string.player_previous), { controller.seekToPrevious() }, size = 48.dp, tint = c.ink, background = Color.Transparent)
        } else {
            IconButton(R.drawable.ic_replay_10, stringResource(R.string.player_back_10), { controller.seekBack() }, size = 48.dp, tint = c.ink, background = Color.Transparent)
        }
        Spacer(Modifier.width(10.dp))
        PlayPause(ui.isPlaying, size = 68.dp, onClick = { if (controller.isPlaying) controller.pause() else controller.play() })
        Spacer(Modifier.width(10.dp))
        if (queue.size > 1) {
            IconButton(R.drawable.ic_skip_next, stringResource(R.string.player_next), { controller.seekToNextMediaItem() }, size = 48.dp, tint = if (ui.hasNext) c.ink else c.ghost, background = Color.Transparent)
        } else {
            IconButton(R.drawable.ic_forward_10, stringResource(R.string.player_forward_10), { controller.seekForward() }, size = 48.dp, tint = c.ink, background = Color.Transparent)
        }
        Box(Modifier.weight(1f), contentAlignment = Alignment.CenterEnd) {
            if (video) IconButton(R.drawable.ic_fullscreen, stringResource(R.string.player_fullscreen), onFullscreen)
        }
    }
    Spacer(Modifier.height(14.dp))
    // Repeat: off, everything, this one. Shuffle only matters for more than one file.
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
        val repeatLabel = stringResource(
            when (ui.repeat) {
                Player.REPEAT_MODE_ONE -> R.string.player_repeat_one
                Player.REPEAT_MODE_ALL -> R.string.player_repeat_all
                else -> R.string.player_repeat_off
            },
        )
        ToggleChip(
            if (ui.repeat == Player.REPEAT_MODE_ONE) R.drawable.ic_repeat_one else R.drawable.ic_repeat,
            repeatLabel, on = ui.repeat != Player.REPEAT_MODE_OFF,
        ) {
            haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
            controller.repeatMode = when (ui.repeat) {
                Player.REPEAT_MODE_OFF -> if (queue.size > 1) Player.REPEAT_MODE_ALL else Player.REPEAT_MODE_ONE
                Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
                else -> Player.REPEAT_MODE_OFF
            }
        }
        if (queue.size > 1) {
            Spacer(Modifier.width(8.dp))
            ToggleChip(R.drawable.ic_shuffle, stringResource(R.string.player_shuffle), on = ui.shuffle) {
                haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
                controller.shuffleModeEnabled = !ui.shuffle
            }
            Spacer(Modifier.width(12.dp))
            val current by Playback.current.collectAsState()
            val at = queue.indexOfFirst { it.id == current?.id } + 1
            Txt("$at / ${queue.size}", style = Hamstr.type.mono, color = c.ghost)
        }
    }
}

/** A small pill that is lit while its mode is on. */
@Composable
private fun ToggleChip(icon: Int, label: String, on: Boolean, onClick: () -> Unit) {
    val c = Hamstr.colors
    val p = animateSelection(on)
    Row(
        Modifier
            .clip(RoundedCornerShape(12.dp))
            .background(lerp(c.surface, c.accent.copy(alpha = 0.18f), p))
            .pressable(onClick, label = label)
            .heightIn(min = 40.dp)
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Icon(icon, tint = lerp(c.muted, c.accent, p), size = 18.dp)
        Txt(label, style = Hamstr.type.small, color = lerp(c.muted, c.accent, p))
    }
}

/** Accent circle; the icon swaps with Slate's scale crossfade. */
@Composable
fun PlayPause(playing: Boolean, size: Dp, onClick: () -> Unit) {
    val c = Hamstr.colors
    val haptics = LocalHapticFeedback.current
    Box(
        Modifier
            .size(size)
            .clip(CircleShape)
            .background(c.accent)
            .pressable({ haptics.performHapticFeedback(HapticFeedbackType.ContextClick); onClick() }, label = stringResource(if (playing) R.string.player_pause else R.string.player_play), pressedScale = 0.9f),
        contentAlignment = Alignment.Center,
    ) {
        AnimatedContent(
            targetState = playing,
            transitionSpec = {
                (scaleIn(spring(dampingRatio = 0.5f, stiffness = 600f), initialScale = 0.4f) + fadeIn(tween(140))) togetherWith
                    (scaleOut(tween(140), targetScale = 0.4f) + fadeOut(tween(110)))
            },
            label = "playPause",
        ) { p -> Icon(if (p) R.drawable.ic_pause else R.drawable.ic_play_arrow, tint = c.onAccent, size = size * 0.45f) }
    }
}

/**
 * Seek bar: drag anywhere on it; the thumb grows while held, stretches with its speed,
 * and the time label follows the finger. Seeks on release.
 */
@Composable
private fun Scrubber(controller: Player, ui: PlayerUi) {
    val c = Hamstr.colors
    val haptics = LocalHapticFeedback.current
    var dragPos by remember { mutableStateOf<Float?>(null) }
    val fraction = dragPos ?: if (ui.duration > 0) ui.position / ui.duration.toFloat() else 0f
    val thumb by animateFloatAsState(if (dragPos != null) 20f else 12f, spring(dampingRatio = 0.5f, stiffness = 600f), label = "thumb")
    BoxWithConstraints(Modifier.fillMaxWidth().height(28.dp)) {
        val w = constraints.maxWidth.toFloat()
        val stretch by rememberVelocityStretch({ fraction * w }, sensitivity = 0.00012f, max = 0.5f)
        Box(
            Modifier
                .fillMaxSize()
                .pointerInput(w) {
                    detectHorizontalDragGestures(
                        onDragStart = { dragPos = (it.x / w).coerceIn(0f, 1f); haptics.performHapticFeedback(HapticFeedbackType.GestureThresholdActivate) },
                        onHorizontalDrag = { ch, dx -> ch.consume(); dragPos = ((dragPos ?: 0f) + dx / w).coerceIn(0f, 1f) },
                        onDragEnd = { dragPos?.let { controller.seekTo((it * ui.duration).toLong()) }; dragPos = null },
                        onDragCancel = { dragPos = null },
                    )
                }
                .pointerInput(w) { detectTapGestures { controller.seekTo(((it.x / w).coerceIn(0f, 1f) * ui.duration).toLong()) } },
        ) {
            Box(Modifier.align(Alignment.CenterStart).fillMaxWidth().height(4.dp).clip(CircleShape).background(c.line))
            Box(Modifier.align(Alignment.CenterStart).fillMaxWidth(fraction.coerceIn(0f, 1f)).height(4.dp).clip(CircleShape).background(c.accent))
            Box(
                Modifier
                    .align(Alignment.CenterStart)
                    .offset { IntOffset((fraction * w - thumb.dp.toPx() / 2).roundToInt(), 0) }
                    .size(thumb.dp)
                    .stretchX { stretch }
                    .clip(CircleShape)
                    .background(c.ink),
            )
        }
    }
    Row(Modifier.fillMaxWidth()) {
        Txt(fmt(((dragPos ?: -1f).takeIf { it >= 0f }?.let { (it * ui.duration).toLong() }) ?: ui.position), style = Hamstr.type.mono, color = if (dragPos != null) c.accent else c.ghost, modifier = Modifier.weight(1f))
        Txt(fmt(ui.duration), style = Hamstr.type.mono, color = c.ghost)
    }
}

private fun fmt(ms: Long): String {
    val s = (ms / 1000).coerceAtLeast(0)
    return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, s / 60 % 60, s % 60) else "%d:%02d".format(s / 60, s % 60)
}
