package app.hamstr.player

import android.content.pm.ActivityInfo
import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalActivity
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.produceState
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.unit.sp
import app.hamstr.data.Lyrics
import app.hamstr.data.MiniStyle
import app.hamstr.data.Prefs
import app.hamstr.data.Playlists
import app.hamstr.ui.components.rubberBand
import app.hamstr.ui.formatDuration
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
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
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.ui.graphics.Brush
import android.content.res.Configuration
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalView
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.gestures.Orientation
import app.hamstr.ui.components.edgeFade
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
import app.hamstr.ui.components.Specs
import app.hamstr.ui.components.iconSwap

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
    // Where a skip goes: shuffle and repeat-all decide, so the mini player shows the right neighbour.
    var nextIndex by mutableIntStateOf(-1)
    var previousIndex by mutableIntStateOf(-1)
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
                ui.nextIndex = player.nextMediaItemIndex
                ui.previousIndex = player.previousMediaItemIndex
            }
        }
        player.addListener(listener)
        ui.isPlaying = player.isPlaying
        ui.speed = player.playbackParameters.speed
        ui.repeat = player.repeatMode
        ui.shuffle = player.shuffleModeEnabled
        ui.hasNext = player.hasNextMediaItem()
        ui.hasPrevious = player.hasPreviousMediaItem()
        ui.nextIndex = player.nextMediaItemIndex
        ui.previousIndex = player.previousMediaItemIndex
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
 * player up to bring it back. Sideways the mini player turns through the queue, with the
 * neighbours sliding in beside it; pulled down it stops; held, it shows the queue. Videos
 * also go fullscreen (landscape) and picture-in-picture when the app is left. With
 * [miniWidth] set (the side bar in landscape) the mini player is a narrower card with its
 * buttons on a second row.
 */
@Composable
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
fun PlayerHost(
    miniBottom: Dp,
    inPip: Boolean,
    onMenu: (DownloadTask) -> Unit,
    onQuality: (DownloadTask) -> Unit,
    onQueue: () -> Unit = {},
    onSleep: () -> Unit = {},
    onEditLyrics: (DownloadTask) -> Unit = {},
    miniStart: Dp = 12.dp,
    miniWidth: Dp? = null,
) {
    val task by Playback.current.collectAsState()
    val controller by Playback.controller.collectAsState()
    if (inPip) {
        Box(Modifier.fillMaxSize().background(Color.Black), contentAlignment = Alignment.Center) {
            if (task?.let(::kindOf) == Kind.Video) {
                controller?.let { PlayerSurface(it, Modifier.fillMaxSize(), SURFACE_TYPE_TEXTURE_VIEW) }
            }
        }
        return
    }

    val t = task ?: return
    val ui = rememberPlayerUi(controller)
    val file = remember(t.id) { t.filePath?.let(::File) }
    val kind = remember(t.id, t.choice.mode) { kindOf(t) }
    val activity = LocalActivity.current

    val c = Hamstr.colors
    val scope = rememberCoroutineScope()
    val haptics = LocalHapticFeedback.current
    val density = LocalDensity.current
    val expandedTarget by Playback.expanded.collectAsState()
    val miniStyle = Prefs.settings.collectAsState().value.miniStyle
    val e = remember { Animatable(0f) }
    // Sideways: the queue turns (the neighbour slides in). Down: the mini player drops away.
    val swipeX = remember { Animatable(0f) }
    val dropY = remember { Animatable(0f) }
    var dragging by remember { mutableStateOf(false) }
    var fullscreen by remember { mutableStateOf(false) }
    var lyricsOn by remember { mutableStateOf(false) }
    val queue by Playback.queue.collectAsState()
    val previous = queue.getOrNull(ui.previousIndex)?.takeIf { queue.size > 1 && it.id != t.id }
    val next = queue.getOrNull(ui.nextIndex)?.takeIf { queue.size > 1 && it.id != t.id }
    val neighbours by rememberUpdatedState(previous to next)
    val settle = spring<Float>(dampingRatio = 0.84f, stiffness = 360f)
    LaunchedEffect(expandedTarget, t.id) {
        swipeX.snapTo(0f)
        dropY.snapTo(0f)
        if (!dragging) e.animateTo(if (expandedTarget) 1f else 0f, settle)
    }
    // A playing video keeps the screen awake, in the app as in picture-in-picture.
    val view = LocalView.current
    DisposableEffect(view, kind, ui.isPlaying) {
        view.keepScreenOn = kind == Kind.Video && ui.isPlaying
        onDispose { view.keepScreenOn = false }
    }
    // Fullscreen turns the phone: upright videos (Shorts, Reels, TikTok) stay upright,
    // wide ones go sideways. Leaving it lets the sensor decide again.
    DisposableEffect(fullscreen) {
        activity?.requestedOrientation = when {
            !fullscreen -> ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            ui.aspect < 0.9f -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
            ui.aspect > 1.1f -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            else -> ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
        onDispose { }
    }
    // A video open on a sideways phone fills the screen the same way, with its controls
    // laid over it. Either way the system bars step aside until it ends.
    val landscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
    val immersive = fullscreen || (landscape && kind == Kind.Video && expandedTarget)
    DisposableEffect(immersive) {
        val window = activity?.window
        if (window != null) {
            val ctl = WindowCompat.getInsetsController(window, window.decorView)
            if (immersive) {
                ctl.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                ctl.hide(WindowInsetsCompat.Type.systemBars())
            } else ctl.show(WindowInsetsCompat.Type.systemBars())
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
    val expandedNow = e.value > 0.001f && !immersive
    BoxWithConstraints(Modifier.fillMaxSize().then(if (expandedNow) shrinkDrag else Modifier)) {
        val W = constraints.maxWidth.toFloat()
        val H = constraints.maxHeight.toFloat()
        val px = { d: Dp -> with(density) { d.toPx() } }
        val top = WindowInsets.statusBars.getTop(density).toFloat()
        val bottomBar = px(miniBottom)

        // Mini player: a 64 dp bar floating above the tab bar, or the foot of the side bar's
        // panel (progress line, picture and title, then the buttons).
        val side = miniWidth != null
        // Upright, the bar floats above the tabs, sits on them like a lip (docked), or is slimmer (compact).
        val style = if (side) MiniStyle.Floating else miniStyle
        val miniH = px(when { side -> 114.dp; style == MiniStyle.Compact -> 52.dp; else -> 64.dp })
        val mini = when (style) {
            // Inside the tab bar's rounded corners, touching its top edge.
            MiniStyle.Docked -> Box4(px(miniStart + 18.dp), H - bottomBar - miniH + px(2.dp), W - px(miniStart * 2 + 36.dp), miniH)
            else -> Box4(px(miniStart), H - bottomBar - miniH - px(8.dp), miniWidth?.let { px(it) } ?: (W - px(24.dp)), miniH)
        }
        val inset = px(if (side) 8.dp else 4.dp)
        val miniMediaH = px(when { side -> 48.dp; style == MiniStyle.Compact -> 44.dp; else -> 56.dp })
        val miniMediaW = if (kind == Kind.Video) miniMediaH * 16f / 9f else miniMediaH
        val miniMedia = Box4(mini.x + inset, mini.y + if (side) px(12.dp) else inset, miniMediaW, miniMediaH)
        // Full view: media under a 56 dp top bar; in landscape it sits left of the info.
        val navBottom = WindowInsets.navigationBars.getBottom(density).toFloat()
        val landscape = W > H
        val fullMedia = when {
            immersive -> Box4(0f, 0f, W, H)
            landscape -> {
                val aspect = when (kind) { Kind.Video -> ui.aspect; Kind.Image -> 16f / 9f; else -> 1f }
                val w = min(W * 0.55f, (H - top - px(56.dp) - navBottom - px(16.dp)) * aspect)
                Box4(px(20.dp), top + px(56.dp), w, w / aspect)
            }
            // Upright videos get more of the screen; the controls still fit below.
            kind == Kind.Video -> Box4(0f, top + px(56.dp), W, min(W / ui.aspect, H * if (ui.aspect < 0.9f) 0.6f else 0.55f))
            kind == Kind.Image -> Box4(0f, top + px(56.dp), W, H - top - px(56.dp) - WindowInsets.navigationBars.getBottom(density) - px(150.dp))
            else -> Box4(px(24.dp), top + px(72.dp), W - px(48.dp), W - px(48.dp))
        }
        val ev = e.value.coerceIn(0f, 1f)
        val media = miniMedia.towards(fullMedia, ev)
        // A song's page takes the colour of its cover, fading into the canvas (as music apps do).
        val tint = rememberCoverColor(if (kind == Kind.Audio) t.thumbnail else null)
        val stretch by rememberVelocityStretch({ e.value * H }, sensitivity = 0.00002f, max = 0.05f)

        // ---- full view backdrop + chrome ------------------------------------------------
        if (ev > 0.001f) {
            Box(
                Modifier
                    .fillMaxSize()
                    .graphicsLayer { alpha = ev }
                    .background(if (immersive) Color.Black else c.canvas)
                    .then(
                        if (kind == Kind.Audio && !immersive) Modifier.background(
                            Brush.verticalGradient(0f to tint.copy(alpha = 0.9f), 0.45f to tint.copy(alpha = 0.35f), 0.8f to Color.Transparent),
                        ) else Modifier,
                    )
                    .pointerInput(Unit) { detectTapGestures { } }, // keep taps off the list below
            )
            if (!immersive) {
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
                    if (kind == Kind.Video || kind == Kind.Audio) IconButton(R.drawable.ic_queue_music, stringResource(R.string.queue_title), onQueue)
                    else IconButton(R.drawable.ic_open_in_new, stringResource(R.string.menu_open_with), { Playback.openWith(activity ?: return@IconButton, t) })
                    IconButton(R.drawable.ic_share, stringResource(R.string.menu_share_file), { Playback.shareFile(activity ?: return@IconButton, t) })
                    IconButton(R.drawable.ic_more_vert, stringResource(R.string.player_more), { onMenu(t) })
                }
                // Info + controls under the media (beside it in landscape). They scroll only when
                // the screen is too short; otherwise a drag here still shrinks the player.
                val infoX = if (landscape) fullMedia.x + fullMedia.w + px(4.dp) else 0f
                val infoY = if (landscape) fullMedia.y else fullMedia.y + fullMedia.h + px(20.dp)
                val infoScroll = rememberScrollState()
                Column(
                    Modifier
                        .offset { IntOffset(infoX.roundToInt(), (infoY + (1f - ev) * px(60.dp)).roundToInt()) }
                        .width(with(density) { (W - infoX).toDp() })
                        .height(with(density) { (H - infoY - navBottom).coerceAtLeast(0f).toDp() })
                        .graphicsLayer { alpha = ((ev - 0.4f) / 0.6f).coerceIn(0f, 1f) }
                        .edgeFade(infoScroll, Orientation.Vertical, length = 22.dp)
                        .verticalScroll(infoScroll, enabled = infoScroll.maxValue in 1 until Int.MAX_VALUE)
                        .padding(horizontal = 20.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            DecodeText(t.title, style = if (kind == Kind.Audio) Hamstr.type.title else Hamstr.type.section, maxLines = 2)
                            // Who made it: the artist for a song, the channel for a video.
                            (t.uploader?.let(app.hamstr.data.Lyrics::cleanArtist) ?: t.source)?.let { who ->
                                Spacer(Modifier.height(4.dp))
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(R.drawable.ic_person, tint = c.ghost, size = 16.dp)
                                    Spacer(Modifier.width(6.dp))
                                    Txt(who, style = Hamstr.type.body, color = c.muted, maxLines = 1)
                                }
                            }
                        }
                        if (kind == Kind.Video || kind == Kind.Audio) FavoriteButton(t)
                    }
                    Spacer(Modifier.height(8.dp))
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
                            if (controller != null) Transport(
                                controller!!, ui, video = kind == Kind.Video, onFullscreen = { fullscreen = true },
                                lyrics = if (kind == Kind.Audio) lyricsOn else null, onLyrics = { lyricsOn = !lyricsOn },
                                onQueue = onQueue, onSleep = onSleep,
                            )
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
        // While the queue turns, the bar draws the pictures itself (the live media stays hidden).
        val carousel = swipeX.value != 0f && ev < 0.02f
        if (ev < 0.999f) {
            val dropFade = (1f - dropY.value / (mini.h * 1.6f)).coerceIn(0f, 1f)
            Box(
                Modifier
                    .offset { IntOffset(mini.x.roundToInt(), (mini.y + ev * px(40.dp) + dropY.value).roundToInt()) }
                    .width(with(density) { mini.w.toDp() })
                    .height(with(density) { mini.h.toDp() })
                    .graphicsLayer {
                        alpha = (1f - ev * 2f).coerceIn(0f, 1f) * dropFade
                        val s = 1f - (1f - dropFade) * 0.08f
                        scaleX = s; scaleY = s
                    }
                    .clip(if (style == MiniStyle.Docked) RoundedCornerShape(topStart = 18.dp, topEnd = 18.dp) else RoundedCornerShape(if (side) 17.dp else 18.dp))
                    .background(when { side -> Color.Transparent; style == MiniStyle.Docked -> c.overlay; else -> c.raised })
                    .pointerInput(Unit) {
                        // Held: the queue. Tapped: the big player.
                        detectTapGestures(
                            onTap = { Playback.expand() },
                            onLongPress = { haptics.performHapticFeedback(HapticFeedbackType.LongPress); onQueue() },
                        )
                    }
                    .pointerInput(Unit) {
                        var axis = 0 // 0 undecided, 1 sideways, 2 up or down
                        var total = Offset.Zero
                        var armed = false
                        val tracker = VelocityTracker()
                        fun arm(now: Boolean) {
                            if (now != armed) {
                                armed = now
                                haptics.performHapticFeedback(if (now) HapticFeedbackType.GestureThresholdActivate else HapticFeedbackType.SegmentTick)
                            }
                        }
                        detectDragGestures(
                            onDragStart = { dragging = true; axis = 0; total = Offset.Zero; armed = false; tracker.resetTracking() },
                            onDragEnd = {
                                dragging = false
                                val v = tracker.calculateVelocity()
                                val (prev, nxt) = neighbours
                                val w = mini.w
                                when {
                                    axis == 1 && nxt != null && (swipeX.value < -w * 0.3f || (v.x < -1400f && swipeX.value < 0f)) -> {
                                        haptics.performHapticFeedback(HapticFeedbackType.Confirm)
                                        scope.launch { swipeX.animateTo(-w, Specs.leave(200)); Playback.skipTo(ui.nextIndex); swipeX.snapTo(0f) }
                                    }
                                    axis == 1 && prev != null && (swipeX.value > w * 0.3f || (v.x > 1400f && swipeX.value > 0f)) -> {
                                        haptics.performHapticFeedback(HapticFeedbackType.Confirm)
                                        scope.launch { swipeX.animateTo(w, Specs.leave(200)); Playback.skipTo(ui.previousIndex); swipeX.snapTo(0f) }
                                    }
                                    axis == 1 -> scope.launch { swipeX.animateTo(0f, Specs.settle()) }
                                    dropY.value > mini.h * 0.6f || (v.y > 1400f && dropY.value > 0f) -> {
                                        haptics.performHapticFeedback(HapticFeedbackType.Confirm)
                                        scope.launch { dropY.animateTo(mini.h * 1.8f, Specs.leave(180)); close() }
                                    }
                                    e.value > 0.2f -> { Playback.expand(); scope.launch { e.animateTo(1f, settle) } }
                                    else -> scope.launch {
                                        launch { dropY.animateTo(0f, Specs.settle()) }
                                        e.animateTo(0f, settle)
                                    }
                                }
                            },
                            onDragCancel = { dragging = false; scope.launch { swipeX.animateTo(0f); dropY.animateTo(0f); e.animateTo(0f, settle) } },
                        ) { change, drag ->
                            change.consume()
                            total += drag
                            tracker.addPosition(change.uptimeMillis, total)
                            if (axis == 0) axis = if (abs(drag.x) > abs(drag.y)) 1 else 2
                            scope.launch {
                                if (axis == 1) {
                                    // Without a neighbour that way, the bar only gives a little.
                                    val (prev, nxt) = neighbours
                                    val toward = if (total.x < 0f) nxt else prev
                                    swipeX.snapTo(if (toward != null) rubberBand(total.x, mini.w * 0.9f, 0.6f) else total.x * 0.12f)
                                    arm(toward != null && abs(swipeX.value) > mini.w * 0.3f)
                                } else if (e.value > 0f || (dropY.value <= 0f && drag.y < 0f)) {
                                    e.snapTo((e.value - drag.y / (H * 0.6f)).coerceIn(0f, 1f))
                                } else {
                                    dropY.snapTo(max(0f, dropY.value + drag.y))
                                    arm(dropY.value > mini.h * 0.6f)
                                }
                            }
                        }
                    },
            ) {
                val playable = kind == Kind.Video || kind == Kind.Audio
                if (side) Column(Modifier.fillMaxSize()) {
                    // The progress line doubles as the divider between the tabs and the player.
                    Box(Modifier.padding(horizontal = 8.dp).fillMaxWidth().height(2.dp).clip(CircleShape).background(c.line)) {
                        if (ui.duration > 0) Box(Modifier.fillMaxWidth(ui.position / ui.duration.toFloat()).fillMaxSize().clip(CircleShape).background(c.accent))
                    }
                    val textStart = with(density) { (miniMediaW + inset + px(12.dp)).toDp() }
                    Box(
                        Modifier.fillMaxWidth().height(66.dp).padding(start = textStart, end = 10.dp, top = 8.dp)
                            .graphicsLayer { translationX = swipeX.value; alpha = 1f - (abs(swipeX.value) / mini.w).coerceIn(0f, 1f) },
                        contentAlignment = Alignment.CenterStart,
                    ) {
                        Txt(t.title, style = Hamstr.type.bodyMedium, maxLines = 2)
                    }
                    Row(Modifier.fillMaxWidth().weight(1f), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
                        IconButton(R.drawable.ic_queue_music, stringResource(R.string.queue_title), onQueue, tint = c.muted, background = Color.Transparent)
                        if (playable) PlayPause(ui.isPlaying, size = 44.dp, onClick = { controller?.let { if (it.isPlaying) it.pause() else it.play() } })
                        IconButton(
                            R.drawable.ic_skip_next, stringResource(R.string.player_next), { controller?.seekToNextMediaItem() },
                            tint = if (ui.hasNext) c.ink else c.ghost, background = Color.Transparent,
                        )
                    }
                } else Row(Modifier.fillMaxSize().padding(end = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    // The picture and title slide; the buttons stay where the thumb expects them.
                    BoxWithConstraints(Modifier.weight(1f).fillMaxHeight().clipToBounds()) {
                        val slot = constraints.maxWidth.toFloat()
                        MiniSlot(t, ui, picture = carousel, inset = inset, mediaH = miniMediaH, modifier = Modifier.offset { IntOffset(swipeX.value.roundToInt(), 0) })
                        if (swipeX.value < 0f) next?.let { MiniSlot(it, null, picture = true, inset = inset, mediaH = miniMediaH, modifier = Modifier.offset { IntOffset((slot + swipeX.value).roundToInt(), 0) }) }
                        if (swipeX.value > 0f) previous?.let { MiniSlot(it, null, picture = true, inset = inset, mediaH = miniMediaH, modifier = Modifier.offset { IntOffset((swipeX.value - slot).roundToInt(), 0) }) }
                    }
                    if (playable) PlayPause(ui.isPlaying, size = if (style == MiniStyle.Compact) 36.dp else 40.dp, onClick = { controller?.let { if (it.isPlaying) it.pause() else it.play() } })
                    if (ui.hasNext) IconButton(R.drawable.ic_skip_next, stringResource(R.string.player_next), { controller?.seekToNextMediaItem() }, background = Color.Transparent)
                    else Spacer(Modifier.width(6.dp))
                }
                // Concentric with the bar's 18 dp corners (the media sits 4 dp in, at 14 dp);
                // the progress line stays on the straight part of the bottom edge.
                if (!side && ui.duration > 0) {
                    Box(
                        Modifier.align(Alignment.BottomStart).padding(horizontal = 18.dp)
                            .fillMaxWidth(ui.position / ui.duration.toFloat()).height(2.dp).clip(CircleShape).background(c.accent),
                    )
                }
            }
        }

        // ---- the media itself, at the morphing rect ----------------------------------
        val m = if (ev < 0.999f) media.copy(y = media.y + dropY.value * (1f - ev)) else media
        Box(
            Modifier
                .offset { IntOffset(m.x.roundToInt(), m.y.roundToInt()) }
                .width(with(density) { m.w.toDp() })
                .height(with(density) { m.h.toDp() })
                .graphicsLayer {
                    alpha = when {
                        carousel -> 0f
                        ev < 0.5f -> (1f - dropY.value / (mini.h * 1.6f)).coerceIn(0f, 1f)
                        else -> 1f
                    }
                    scaleY = 1f + abs(stretch)
                }
                .clip(RoundedCornerShape(if (immersive) 0.dp else lerp(14f, if (kind == Kind.Video || kind == Kind.Image) 0f else 16f, ev).dp))
                .background(if (kind == Kind.Video) Color.Black else c.black),
        ) {
            when (kind) {
                Kind.Video -> VideoSurface(
                    controller, ui, t, expanded = ev > 0.98f, immersive = immersive, forced = fullscreen,
                    onExitFullscreen = { fullscreen = false },
                    onTapMini = { Playback.expand() },
                    onMinimize = { fullscreen = false; Playback.minimize() },
                    onMenu = { fullscreen = false; onMenu(t) },
                    onQuality = if (canHaveVersions(t)) ({ fullscreen = false; onQuality(t) }) else null,
                )
                Kind.Audio -> AnimatedContent(lyricsOn && ev > 0.98f, transitionSpec = { fadeIn(Specs.appear()) togetherWith fadeOut(Specs.disappear()) }, label = "lyrics") { on ->
                    if (on && controller != null) LyricsPanel(t, controller!!, ui, onEdit = { onEditLyrics(t) })
                    else Cover(t, ev)
                }
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
private fun VideoSurface(
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
                    IconButton(R.drawable.ic_expand_more, stringResource(R.string.player_minimize), onMinimize, tint = Color.White, background = Color.Transparent)
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
                                Icon(R.drawable.ic_expand_more, tint = c.accent, size = 16.dp)
                            } else Badge(formatBadge(t))
                        }
                    }
                    // One pill for the actions, so they read as a group rather than loose buttons.
                    Row(Modifier.clip(RoundedCornerShape(16.dp)).background(Color.Black.copy(alpha = 0.35f))) {
                        val none = Color.Transparent
                        IconButton(R.drawable.ic_open_in_new, stringResource(R.string.menu_open_with), { Playback.openWith(context, t) }, tint = Color.White, background = none)
                        IconButton(R.drawable.ic_share, stringResource(R.string.menu_share_file), { Playback.shareFile(context, t) }, tint = Color.White, background = none)
                        IconButton(R.drawable.ic_more_vert, stringResource(R.string.player_more), onMenu, tint = Color.White, background = none)
                        if (forced) IconButton(R.drawable.ic_fullscreen_exit, stringResource(R.string.player_exit_fullscreen), onExitFullscreen, tint = Color.White, background = none)
                    }
                }
                Row(Modifier.align(Alignment.Center), horizontalArrangement = Arrangement.spacedBy(28.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(R.drawable.ic_replay_10, stringResource(R.string.player_back_10), { controller.seekBack() }, size = 48.dp, tint = Color.White, background = Color.Transparent)
                    PlayPause(ui.isPlaying, size = 64.dp, onClick = { if (controller.isPlaying) controller.pause() else controller.play() })
                    IconButton(R.drawable.ic_forward_10, stringResource(R.string.player_forward_10), { controller.seekForward() }, size = 48.dp, tint = Color.White, background = Color.Transparent)
                }
                Column(Modifier.align(Alignment.BottomCenter).padding(horizontal = 28.dp, vertical = 14.dp)) {
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
private fun Transport(
    controller: Player,
    ui: PlayerUi,
    video: Boolean,
    onFullscreen: () -> Unit,
    lyrics: Boolean?,
    onLyrics: () -> Unit,
    onQueue: () -> Unit,
    onSleep: () -> Unit,
) {
    if (!video && lyrics != null) return SongTransport(controller, ui, lyrics, onLyrics, onQueue, onSleep)
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
    // The modes in one row: repeat, shuffle, the sleep timer, lyrics and the queue. It
    // scrolls sideways when the screen is narrow and sits centred when it fits.
    val sleep by Playback.sleep.collectAsState()
    val now by produceState(System.currentTimeMillis(), sleep) {
        while (sleep is Playback.Sleep.At) { value = System.currentTimeMillis(); delay(1000) }
    }
    val chips = rememberScrollState()
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        Row(
            Modifier.edgeFade(chips, Orientation.Horizontal).horizontalScroll(chips),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
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
            if (queue.size > 1) ToggleChip(R.drawable.ic_shuffle, stringResource(R.string.player_shuffle), on = ui.shuffle) {
                haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
                controller.shuffleModeEnabled = !ui.shuffle
            }
            ToggleChip(
                R.drawable.ic_timer,
                when (val s = sleep) {
                    is Playback.Sleep.At -> fmt((s.endsAt - now).coerceAtLeast(0))
                    Playback.Sleep.EndOfTrack -> stringResource(R.string.sleep_end_short)
                    null -> stringResource(R.string.sleep)
                },
                on = sleep != null, mono = sleep is Playback.Sleep.At, onClick = onSleep,
            )
            if (lyrics != null) ToggleChip(R.drawable.ic_lyrics, stringResource(R.string.lyrics), on = lyrics) {
                haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
                onLyrics()
            }
            val current by Playback.current.collectAsState()
            val at = queue.indexOfFirst { it.id == current?.id } + 1
            ToggleChip(
                R.drawable.ic_queue_music,
                if (queue.size > 1) "$at / ${queue.size}" else stringResource(R.string.queue_title),
                on = false, mono = queue.size > 1, onClick = onQueue,
            )
        }
    }
}

/** A small pill that is lit while its mode is on. */
@Composable
private fun ToggleChip(icon: Int, label: String, on: Boolean, mono: Boolean = false, onClick: () -> Unit) {
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
        Txt(label, style = if (mono) Hamstr.type.mono else Hamstr.type.small, color = lerp(c.muted, c.accent, p), maxLines = 1)
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
            transitionSpec = { iconSwap() },
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

/** One entry in the mini player: picture, title and a line under it. [ui] is set for what plays now. */
@Composable
private fun MiniSlot(t: DownloadTask, ui: PlayerUi?, picture: Boolean, inset: Float, mediaH: Float, modifier: Modifier) {
    val c = Hamstr.colors
    val density = LocalDensity.current
    val h = with(density) { mediaH.toDp() }
    val w = if (kindOf(t) == Kind.Video) h * 16f / 9f else h
    Row(modifier.fillMaxSize().padding(start = with(density) { inset.toDp() }), verticalAlignment = Alignment.CenterVertically) {
        // What plays now is drawn by the morphing media on top; only a turning queue needs pictures here.
        Box(Modifier.width(w).height(h).clip(RoundedCornerShape(14.dp)).background(if (picture) c.black else Color.Transparent)) {
            if (picture) AsciiImage(rememberBitmap(t.thumbnail, 240), reveal = 1f, cell = 4.dp, modifier = Modifier.fillMaxSize())
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Txt(t.title, style = Hamstr.type.bodyMedium, maxLines = 1)
            Spacer(Modifier.height(2.dp))
            val line = if (ui != null && ui.duration > 0) "${fmt(ui.position)} / ${fmt(ui.duration)}"
            else listOfNotNull(t.uploader?.let(Lyrics::cleanArtist) ?: t.source, formatDuration(t.durationSec).takeIf { t.durationSec > 0 }).joinToString(" · ")
            Txt(line, style = Hamstr.type.mono, color = c.ghost, maxLines = 1)
        }
    }
}

/** The heart: puts what plays into Favorites, or takes it out. */
@Composable
private fun FavoriteButton(t: DownloadTask) {
    val c = Hamstr.colors
    val haptics = LocalHapticFeedback.current
    val lists by Playlists.lists.collectAsState()
    val fav = remember(lists, t.id) { Playlists.isFavorite(t) }
    val name = stringResource(R.string.pl_favorites)
    AnimatedContent(fav, transitionSpec = { iconSwap() }, label = "favorite") { on ->
        IconButton(
            if (on) R.drawable.ic_favorite else R.drawable.ic_favorite_border,
            stringResource(if (on) R.string.fav_remove else R.string.fav_add),
            {
                haptics.performHapticFeedback(if (on) HapticFeedbackType.ToggleOff else HapticFeedbackType.ToggleOn)
                Playlists.toggleFavorite(t, name)
            },
            tint = if (on) c.accent else c.muted, background = Color.Transparent,
        )
    }
}

private enum class LyricsLoad { Loading, Searching, Ready, Missing }

/**
 * A song's lyrics where its cover was. Saved ones show at once; otherwise LRCLIB is asked
 * and what it finds is kept. Timed lyrics follow the song (the line that is sung lights up
 * and stays in view) and a tap on a line jumps there.
 */
@Composable
private fun LyricsPanel(t: DownloadTask, controller: Player, ui: PlayerUi, onEdit: () -> Unit) {
    val c = Hamstr.colors
    val saved by Lyrics.saved.collectAsState()
    var load by remember(t.id) { mutableStateOf(LyricsLoad.Loading) }
    var attempt by remember(t.id) { mutableIntStateOf(0) }
    LaunchedEffect(t.id, attempt) {
        if (attempt == 0 && Lyrics.load(t.id) != null) { load = LyricsLoad.Ready; return@LaunchedEffect }
        load = LyricsLoad.Searching
        load = Lyrics.find(t).fold({ Lyrics.save(t.id, it); LyricsLoad.Ready }, { LyricsLoad.Missing })
    }
    val text = saved[t.id]
    val lines = remember(text) { text?.let(Lyrics::parse).orEmpty() }
    Box(Modifier.fillMaxSize().background(c.overlay)) {
        when {
            lines.isNotEmpty() -> LyricLines(lines, controller, ui)
            load == LyricsLoad.Missing -> Column(
                Modifier.align(Alignment.Center).padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Icon(R.drawable.ic_lyrics, tint = c.ghost, size = 32.dp)
                Spacer(Modifier.height(10.dp))
                Txt(stringResource(R.string.lyrics_none), style = Hamstr.type.bodyMedium)
                Spacer(Modifier.height(4.dp))
                Txt(stringResource(R.string.lyrics_none_hint), style = Hamstr.type.small, color = c.muted)
                Spacer(Modifier.height(14.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    HButton(stringResource(R.string.lyrics_retry), { attempt++ }, kind = ButtonKind.Neutral, icon = R.drawable.ic_refresh)
                    HButton(stringResource(R.string.lyrics_add), onEdit, kind = ButtonKind.Primary, icon = R.drawable.ic_edit)
                }
            }
            else -> Txt(
                stringResource(if (load == LyricsLoad.Searching) R.string.lyrics_searching else R.string.lyrics_loading),
                style = Hamstr.type.mono, color = c.ghost, modifier = Modifier.align(Alignment.Center),
            )
        }
        if (lines.isNotEmpty()) IconButton(
            R.drawable.ic_edit, stringResource(R.string.lyrics_edit), onEdit,
            modifier = Modifier.align(Alignment.TopEnd).padding(4.dp), tint = c.muted, background = c.overlay,
        )
    }
}

@Composable
private fun LyricLines(lines: List<Lyrics.Line>, controller: Player, ui: PlayerUi) {
    val c = Hamstr.colors
    val timed = lines.first().at >= 0
    val list = rememberLazyListState()
    // A little ahead, so the line lights up as it is sung rather than after.
    val now = if (timed) lines.indexOfLast { it.at <= ui.position + 250 } else -1
    LaunchedEffect(now) {
        if (now >= 0 && !list.isScrollInProgress) list.animateScrollToItem(now, -(list.layoutInfo.viewportSize.height * 0.35f).toInt())
    }
    LazyColumn(
        Modifier.fillMaxSize().edgeFade(list, Orientation.Vertical, length = 22.dp),
        state = list,
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 44.dp, bottom = 120.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        itemsIndexed(lines) { i, l ->
            val p = animateSelection(i == now)
            Txt(
                l.text.ifEmpty { "♪" },
                style = if (timed) Hamstr.type.section.copy(fontSize = 20.sp, lineHeight = 26.sp) else Hamstr.type.body.copy(fontSize = 16.sp, lineHeight = 23.sp),
                color = if (timed) lerp(c.ghost, c.ink, p) else c.ink,
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .then(if (timed) Modifier.clickable { controller.seekTo(l.at); controller.play() } else Modifier)
                    .padding(vertical = 2.dp),
            )
        }
    }
}

/** The average colour of a cover, toned so white text stays readable on it; eases between songs. */
@Composable
private fun rememberCoverColor(url: String?): Color {
    val c = Hamstr.colors
    val bitmap = rememberBitmap(url, 96)
    val target = remember(bitmap) {
        bitmap?.let { b ->
            val hsv = FloatArray(3)
            android.graphics.Color.colorToHSV(android.graphics.Bitmap.createScaledBitmap(b, 1, 1, true).getPixel(0, 0), hsv)
            hsv[1] = (hsv[1] * 1.25f).coerceAtMost(0.7f)
            hsv[2] = hsv[2].coerceIn(0.3f, 0.55f)
            Color(android.graphics.Color.HSVToColor(hsv))
        }
    }
    return animateColorAsState(target ?: c.overlay, tween(600), label = "coverColor").value
}

/**
 * A song's controls, laid out like a music app's: the timeline, then shuffle, previous,
 * play, next and repeat in one row, and under it the sleep timer and speed on the left,
 * lyrics and the queue on the right. A lit mode is accent with a dot under it.
 */
@Composable
private fun SongTransport(controller: Player, ui: PlayerUi, lyrics: Boolean, onLyrics: () -> Unit, onQueue: () -> Unit, onSleep: () -> Unit) {
    val c = Hamstr.colors
    val haptics = LocalHapticFeedback.current
    val queue by Playback.queue.collectAsState()
    Scrubber(controller, ui)
    Spacer(Modifier.height(10.dp))
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        ModeButton(R.drawable.ic_shuffle, stringResource(R.string.player_shuffle), on = ui.shuffle, enabled = queue.size > 1) {
            haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
            controller.shuffleModeEnabled = !ui.shuffle
        }
        IconButton(R.drawable.ic_skip_previous, stringResource(R.string.player_previous), { controller.seekToPrevious() }, size = 52.dp, tint = c.ink, background = Color.Transparent)
        PlayPause(ui.isPlaying, size = 72.dp, onClick = { if (controller.isPlaying) controller.pause() else controller.play() })
        IconButton(R.drawable.ic_skip_next, stringResource(R.string.player_next), { controller.seekToNextMediaItem() }, size = 52.dp, tint = if (ui.hasNext) c.ink else c.ghost, background = Color.Transparent)
        ModeButton(
            if (ui.repeat == Player.REPEAT_MODE_ONE) R.drawable.ic_repeat_one else R.drawable.ic_repeat,
            stringResource(
                when (ui.repeat) {
                    Player.REPEAT_MODE_ONE -> R.string.player_repeat_one
                    Player.REPEAT_MODE_ALL -> R.string.player_repeat_all
                    else -> R.string.player_repeat_off
                },
            ),
            on = ui.repeat != Player.REPEAT_MODE_OFF,
        ) {
            haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
            controller.repeatMode = when (ui.repeat) {
                Player.REPEAT_MODE_OFF -> if (queue.size > 1) Player.REPEAT_MODE_ALL else Player.REPEAT_MODE_ONE
                Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
                else -> Player.REPEAT_MODE_OFF
            }
        }
    }
    Spacer(Modifier.height(14.dp))
    val sleep by Playback.sleep.collectAsState()
    val now by produceState(System.currentTimeMillis(), sleep) {
        while (sleep is Playback.Sleep.At) { value = System.currentTimeMillis(); delay(1000) }
    }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        ToggleChip(
            R.drawable.ic_timer,
            when (val s = sleep) {
                is Playback.Sleep.At -> fmt((s.endsAt - now).coerceAtLeast(0))
                Playback.Sleep.EndOfTrack -> stringResource(R.string.sleep_end_short)
                null -> stringResource(R.string.sleep)
            },
            on = sleep != null, mono = sleep is Playback.Sleep.At, onClick = onSleep,
        )
        Spacer(Modifier.width(8.dp))
        val speeds = listOf(1f, 1.25f, 1.5f, 2f, 0.75f)
        Box(
            Modifier
                .clip(RoundedCornerShape(12.dp))
                .background(if (abs(ui.speed - 1f) > 0.01f) c.accent.copy(alpha = 0.18f) else c.surface)
                .pressable({
                    haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
                    controller.setPlaybackSpeed(speeds[(speeds.indexOfFirst { abs(it - ui.speed) < 0.01f } + 1) % speeds.size])
                }, label = stringResource(R.string.player_speed))
                .heightIn(min = 40.dp)
                .padding(horizontal = 12.dp),
            contentAlignment = Alignment.Center,
        ) { Txt("${if (ui.speed % 1f == 0f) ui.speed.toInt() else ui.speed}×", style = Hamstr.type.mono, color = if (abs(ui.speed - 1f) > 0.01f) c.accent else c.muted) }
        Spacer(Modifier.weight(1f))
        ModeButton(R.drawable.ic_lyrics, stringResource(R.string.lyrics), on = lyrics) {
            haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
            onLyrics()
        }
        ModeButton(R.drawable.ic_queue_music, stringResource(R.string.queue_title), on = false, onClick = onQueue)
    }
}

/** An icon that is accent with a dot under it while its mode is on. */
@Composable
private fun ModeButton(icon: Int, label: String, on: Boolean, enabled: Boolean = true, onClick: () -> Unit) {
    val c = Hamstr.colors
    val p = animateSelection(on)
    Box(contentAlignment = Alignment.Center) {
        IconButton(icon, label, { if (enabled) onClick() }, tint = if (!enabled) c.line else lerp(c.muted, c.accent, p), background = Color.Transparent, size = 44.dp)
        Box(
            Modifier.align(Alignment.BottomCenter).padding(bottom = 4.dp).size(4.dp)
                .graphicsLayer { alpha = p; scaleX = p; scaleY = p }
                .clip(CircleShape).background(c.accent),
        )
    }
}
