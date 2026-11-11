package app.hamstr.player

import app.hamstr.res.*
import android.content.pm.ActivityInfo
import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalActivity
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.input.pointer.util.VelocityTracker
import app.hamstr.data.Lyrics
import app.hamstr.data.MiniStyle
import app.hamstr.data.Prefs
import app.hamstr.ui.components.rubberBand
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
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
import androidx.compose.ui.graphics.Brush
import android.content.res.Configuration
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalView
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.gestures.Orientation
import app.hamstr.ui.components.edgeFade
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
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
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
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import app.hamstr.data.DownloadTask
import app.hamstr.ui.components.Badge
import app.hamstr.ui.components.ButtonKind
import app.hamstr.ui.components.DecodeText
import app.hamstr.ui.components.HButton
import app.hamstr.ui.components.Icon
import app.hamstr.ui.components.IconButton
import app.hamstr.ui.components.Txt
import app.hamstr.ui.components.rememberVelocityStretch
import app.hamstr.ui.formatBadge
import app.hamstr.ui.canHaveVersions
import app.hamstr.ui.theme.Hamstr
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import androidx.compose.ui.graphics.lerp
import app.hamstr.ui.components.Specs

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
                // Paused seeks and newly prepared media update through events, without polling.
                ui.position = player.currentPosition
                ui.duration = player.duration.coerceAtLeast(0L)
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
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(player, lifecycle, ui.isPlaying) {
        if (player == null) return@LaunchedEffect
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            do {
                ui.position = player.currentPosition
                ui.duration = player.duration.coerceAtLeast(0L)
                if (!player.isPlaying) break
                delay(200)
            } while (player.isPlaying)
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
    onShare: (DownloadTask) -> Unit = onMenu,
    onArtist: (DownloadTask) -> Unit = {},
    onQueue: () -> Unit = {},
    onSleep: () -> Unit = {},
    onEditLyrics: (DownloadTask) -> Unit = {},
    onDownloadedRemote: () -> Unit = {},
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
    val isRemote = t.id.startsWith(app.hamstr.cast.HamstrCast.REMOTE)
    val remoteDownloading by PhoneStage.downloading.collectAsState()
    val remoteDownloaded by PhoneStage.downloaded.collectAsState()
    val isRemoteDownloaded = t.id in remoteDownloaded
    val context = androidx.compose.ui.platform.LocalContext.current
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
    var lyricsFull by remember { mutableStateOf(false) }
    // A song's drawer on an upright phone: the queue and the lyrics, pulled up from the foot.
    val drawer = remember { Animatable(0f) }
    var drawerTab by remember { mutableStateOf(DrawerTab.Queue) }
    // Where the row of play buttons ends below the top of the title: the drawer's half stop
    // goes under it, and the player makes room for that.
    var controlsInInfo by remember { mutableFloatStateOf(0f) }
    val infoBox = remember { arrayOfNulls<androidx.compose.ui.layout.LayoutCoordinates>(1) }
    LaunchedEffect(expandedTarget) { if (!expandedTarget) drawer.snapTo(0f) }
    val queue by Playback.queue.collectAsState()
    val previous = queue.getOrNull(ui.previousIndex)?.takeIf { queue.size > 1 && it.id != t.id }
    val next = queue.getOrNull(ui.nextIndex)?.takeIf { queue.size > 1 && it.id != t.id }
    val neighbours by rememberUpdatedState(previous to next)
    val settle = spring<Float>(dampingRatio = 0.84f, stiffness = 360f)
    val downloadRemote: () -> Unit = {
        if (!isRemoteDownloaded && !remoteDownloading) {
            scope.launch { if (PhoneStage.download(context)) onDownloadedRemote() }
        }
    }
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
    // Fullscreen lyrics belong to the song on the big player; they go when either does.
    val lyricsOver = lyricsFull && kind == Kind.Audio && expandedTarget && controller != null
    DisposableEffect(immersive || lyricsOver) {
        val window = activity?.window
        if (window != null) {
            val ctl = WindowCompat.getInsetsController(window, window.decorView)
            if (immersive || lyricsOver) {
                ctl.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                ctl.hide(WindowInsetsCompat.Type.systemBars())
            } else ctl.show(WindowInsetsCompat.Type.systemBars())
        }
        onDispose { }
    }
    BackHandler(enabled = fullscreen || expandedTarget) {
        if (fullscreen) fullscreen = false else Playback.minimize()
    }
    BackHandler(enabled = lyricsOver) { lyricsFull = false }
    LaunchedEffect(lyricsOver) { if (!lyricsOver) lyricsFull = false }

    fun close() = scope.launch {
        fullscreen = false
        Playback.close()
    }

    // Vertical drags on the big player shrink it (either direction); release decides. On the
    // video itself a drag up pulls it into fullscreen instead, as in video apps: the picture
    // grows with the finger and goes fullscreen if let go past the threshold.
    val lift = remember { Animatable(0f) }
    var videoArea by remember { mutableStateOf(androidx.compose.ui.geometry.Rect.Zero) }
    val shrinkDrag = Modifier.pointerInput(Unit) {
        var travel = 0f
        var start = Offset.Zero
        var up: Boolean? = null
        var armed = false
        val arm = 72.dp.toPx()
        detectVerticalDragGestures(
            onDragStart = { at -> dragging = true; travel = 0f; start = at; up = null; armed = false },
            onVerticalDrag = { change, dy ->
                change.consume()
                travel += dy
                val toFullscreen = up ?: (dy < 0f && videoArea.contains(start)).also { up = it }
                if (toFullscreen) {
                    val pulled = rubberBand((-travel).coerceAtLeast(0f), arm * 1.5f)
                    if ((pulled > arm) != armed) {
                        armed = !armed
                        haptics.performHapticFeedback(if (armed) HapticFeedbackType.GestureThresholdActivate else HapticFeedbackType.SegmentTick)
                    }
                    scope.launch { lift.snapTo(pulled) }
                } else scope.launch { e.snapTo((1f - abs(travel) / (size.height * 0.55f)).coerceIn(0.02f, 1f)) }
            },
            onDragEnd = {
                dragging = false
                when {
                    up == true -> {
                        if (armed) {
                            haptics.performHapticFeedback(HapticFeedbackType.Confirm)
                            fullscreen = true
                        }
                        scope.launch { lift.animateTo(0f, settle) }
                    }
                    e.value < 0.75f -> {
                        haptics.performHapticFeedback(HapticFeedbackType.GestureThresholdActivate)
                        Playback.minimize()
                        scope.launch { e.animateTo(0f, settle) }
                    }
                    else -> scope.launch { e.animateTo(1f, settle) }
                }
            },
            onDragCancel = { dragging = false; scope.launch { lift.animateTo(0f, settle); e.animateTo(1f, settle) } },
        )
    }

    // Only the expanded player claims the whole screen; the mini player leaves the list usable.
    val expandedNow = e.value > 0.001f && !immersive && !lyricsOver
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
        val useDrawer = kind == Kind.Audio && !landscape && controller != null
        // The drawer's half stop, as music apps have it: the list takes the lower half, and the
        // cover shrinks (down to 140 dp) so the title and the play buttons still sit above it.
        val cover = W - px(48.dp)
        val closedTop = H - navBottom - px(DRAWER_CLOSED)
        val openTop = top + px(76.dp)
        var halfTop: Float? = null
        var squeeze = 0f
        var halfShare: Float? = null
        if (useDrawer && controlsInInfo > 0f) {
            val coverTop = top + px(72.dp)
            val minCover = min(cover, px(140.dp))
            var want = H * 0.5f
            var s = (coverTop + cover + px(20.dp) + controlsInInfo + px(14.dp) - want).coerceAtLeast(0f)
            if (cover - s < minCover) { want += minCover - (cover - s); s = cover - minCover }
            drawerHalf(closedTop, openTop, want, px(160.dp))?.let { half ->
                halfTop = want
                halfShare = half
                squeeze = s * (drawer.value / half).coerceIn(0f, 1f)
            }
        }
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
            else -> Box4(px(24.dp) + squeeze / 2f, top + px(72.dp), cover - squeeze, cover - squeeze)
        }
        val ev = e.value.coerceIn(0f, 1f)
        val media = miniMedia.towards(fullMedia, ev)
        SideEffect {
            videoArea = if (kind == Kind.Video && !immersive) androidx.compose.ui.geometry.Rect(fullMedia.x, fullMedia.y, fullMedia.x + fullMedia.w, fullMedia.y + fullMedia.h)
                else androidx.compose.ui.geometry.Rect.Zero
        }
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
                        .graphicsLayer {
                            // Under the drawer's song row (on its way past the half stop) it fades out.
                            val upper = if (!useDrawer) 0f else halfShare?.let { ((drawer.value - it) / (1f - it)).coerceIn(0f, 1f) } ?: drawer.value
                            alpha = ((ev - 0.5f) * 2f).coerceIn(0f, 1f) * (1f - upper * 2.5f).coerceIn(0f, 1f)
                        }
                        .padding(horizontal = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(Res.drawable.ic_expand_more, stringResource(R.string.player_minimize), { Playback.minimize() }, tint = c.ink)
                    Spacer(Modifier.width(12.dp))
                    Txt(
                        stringResource(when (kind) { Kind.Video -> R.string.player_video; Kind.Audio -> R.string.player_now_playing; Kind.Image -> R.string.player_image; Kind.Other -> R.string.player_file }),
                        style = Hamstr.type.bodyMedium, color = c.muted, modifier = Modifier.weight(1f),
                    )
                    if (useDrawer) {
                        // The queue is in the drawer; casting moves up here.
                        val casting by app.hamstr.cast.Cast.session.collectAsState()
                        IconButton(Res.drawable.ic_cast, stringResource(R.string.cast_button), { CastSheetState.open.value = true }, tint = if (casting != null) c.accent else c.muted)
                    } else if (kind == Kind.Video || kind == Kind.Audio) IconButton(Res.drawable.ic_queue_music, stringResource(R.string.queue_title), onQueue)
                    else IconButton(Res.drawable.ic_open_in_new, stringResource(R.string.menu_open_with), { Playback.openWith(activity ?: return@IconButton, t) })
                    if (isRemote) {
                        IconButton(
                            if (isRemoteDownloaded) Res.drawable.ic_check else Res.drawable.ic_download,
                            stringResource(if (isRemoteDownloaded) R.string.import_done_one else R.string.share_stash),
                            downloadRemote,
                            tint = if (isRemoteDownloaded || remoteDownloading) c.accent else c.ink,
                        )
                    } else {
                        IconButton(Res.drawable.ic_share, stringResource(R.string.menu_share_file), { onShare(t) })
                        IconButton(Res.drawable.ic_more_vert, stringResource(R.string.player_more), { onMenu(t) })
                    }

                }
                // Info + controls under the media (beside it in landscape). They scroll only when
                // the screen is too short; otherwise a drag here still shrinks the player.
                val infoX = if (landscape) fullMedia.x + fullMedia.w + px(4.dp) else 0f
                val infoY = if (landscape) fullMedia.y else fullMedia.y + fullMedia.h + px(20.dp)
                val infoScroll = rememberScrollState()
                Column(
                    Modifier
                        .offset { IntOffset(infoX.roundToInt(), (infoY + (1f - ev) * px(60.dp)).roundToInt()) }
                        .onPlaced { infoBox[0] = it }
                        .width(with(density) { (W - infoX).toDp() })
                        .height(with(density) { (H - infoY - navBottom - if (useDrawer) px(DRAWER_CLOSED) else 0f).coerceAtLeast(0f).toDp() })
                        .graphicsLayer { alpha = ((ev - 0.4f) / 0.6f).coerceIn(0f, 1f) }
                        .edgeFade(infoScroll, Orientation.Vertical, length = 22.dp)
                        .verticalScroll(infoScroll, enabled = infoScroll.maxValue in 1 until Int.MAX_VALUE)
                        .padding(horizontal = 20.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            DecodeText(t.title, style = if (kind == Kind.Audio) Hamstr.type.title else Hamstr.type.section, maxLines = 2)
                            // Who made it: the artist for a song, the channel for a video. A tap
                            // shows them, to follow or to pick more of their videos.
                            (t.uploader?.let(app.hamstr.data.Lyrics::cleanArtist) ?: t.source)?.let { who ->
                                Spacer(Modifier.height(4.dp))
                                Row(
                                    Modifier.clip(RoundedCornerShape(8.dp)).clickable(onClickLabel = stringResource(R.string.follow_button)) { onArtist(t) }
                                        .padding(end = 6.dp, top = 2.dp, bottom = 2.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Icon(Res.drawable.ic_person, tint = c.ghost, size = 16.dp)
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
                            Icon(Res.drawable.ic_expand_more, tint = c.accent, size = 16.dp)
                        }
                    }
                    Spacer(Modifier.height(18.dp))
                    when (kind) {
                        Kind.Video, Kind.Audio -> {
                            if (controller != null) Transport(
                                controller!!, ui, video = kind == Kind.Video, onFullscreen = { fullscreen = true },
                                lyrics = if (kind == Kind.Audio) lyricsOn else null, onLyrics = { lyricsOn = !lyricsOn },
                                onQueue = onQueue, onSleep = onSleep, drawer = useDrawer,
                                controls = if (useDrawer) Modifier.onGloballyPositioned { row ->
                                    val info = infoBox[0]?.takeIf { it.isAttached } ?: return@onGloballyPositioned
                                    if (!row.isAttached) return@onGloballyPositioned
                                    val bottom = info.localBoundingBoxOf(row, clipBounds = false).bottom
                                    if (abs(bottom - controlsInInfo) > 1f) controlsInInfo = bottom
                                } else Modifier,
                            )
                        }
                        Kind.Image -> {}
                        Kind.Other -> {
                            Txt(stringResource(R.string.player_cant_show, file?.extension ?: "?"), color = c.muted)
                            Spacer(Modifier.height(14.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                HButton(stringResource(R.string.menu_open_with), { activity?.let { Playback.openWith(it, t) } }, Modifier.weight(1f), kind = ButtonKind.Primary, icon = Res.drawable.ic_open_in_new)
                                HButton(stringResource(R.string.menu_share_file), { onShare(t) }, Modifier.weight(1f), icon = Res.drawable.ic_share)
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
                        IconButton(Res.drawable.ic_queue_music, stringResource(R.string.queue_title), onQueue, tint = c.muted, background = Color.Transparent)
                        if (playable) PlayPause(ui.isPlaying, size = 44.dp, onClick = { controller?.let { if (it.isPlaying) it.pause() else it.play() } })
                        IconButton(
                            Res.drawable.ic_skip_next, stringResource(R.string.player_next), { if (isRemote) PhoneStage.next() else controller?.seekToNextMediaItem() },
                            tint = if (ui.hasNext || isRemote) c.ink else c.ghost, background = Color.Transparent,
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
                    if (ui.hasNext || isRemote) IconButton(Res.drawable.ic_skip_next, stringResource(R.string.player_next), { if (isRemote) PhoneStage.next() else controller?.seekToNextMediaItem() }, background = Color.Transparent)
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
                    // Pulled up towards fullscreen, the picture grows a little with the finger.
                    val grow = 1f + lift.value / H * 0.6f
                    scaleX = grow
                    scaleY = (1f + abs(stretch)) * grow
                    translationY = -lift.value * 0.3f
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
                    onShare = { fullscreen = false; onShare(t) },
                    onQuality = if (canHaveVersions(t)) ({ fullscreen = false; onQuality(t) }) else null,
                    onDownloadRemote = if (isRemote) downloadRemote else null,
                    remoteDownloaded = isRemoteDownloaded,
                )
                // The crossfade keeps to the morphing box: one that eased its own size would lag
                // behind it while the player is pulled down, and show at the bottom and the side.
                Kind.Audio -> AnimatedContent(
                    lyricsOn && ev > 0.98f && !useDrawer,
                    Modifier.fillMaxSize(),
                    transitionSpec = { (fadeIn(Specs.appear()) togetherWith fadeOut(Specs.disappear())).using(null) },
                    label = "lyrics",
                ) { on ->
                    if (on && controller != null) LyricsPanel(t, controller!!, ui, onEdit = { onEditLyrics(t) }, onFullscreen = { lyricsFull = true })
                    else Cover(
                        t, ev,
                        previous = previous,
                        next = next,
                        onPrevious = when {
                            isRemote -> ({ PhoneStage.previous() })
                            previous != null -> ({ Playback.skipTo(ui.previousIndex) })
                            ui.position > 3_000L -> ({ controller?.seekTo(0L) })
                            else -> null
                        },
                        onNext = when {
                            isRemote -> ({ PhoneStage.next() })
                            next != null -> ({ Playback.skipTo(ui.nextIndex) })
                            else -> null
                        },
                    )
                }
                Kind.Image -> ImageView(file, zoomable = ev > 0.98f)
                Kind.Other -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(Res.drawable.ic_description, tint = c.accent, size = lerp(22f, 64f, ev).dp)
                        if (ev > 0.5f) Badge(file?.extension ?: stringResource(R.string.player_file), accent = true)
                    }
                }
            }
        }

        // ---- the drawer: queue and lyrics, pulled up over the song ----------------------
        if (useDrawer && ev > 0.001f) {
            Box(Modifier.graphicsLayer { alpha = ((ev - 0.4f) / 0.6f).coerceIn(0f, 1f); translationY = (1f - ev) * px(60.dp) }) {
                UpNextDrawer(
                    t, controller!!, ui, drawer, drawerTab, { drawerTab = it },
                    width = W, height = H,
                    closedTop = closedTop, openTop = openTop, navBottom = navBottom,
                    halfTop = halfTop,
                    tint = tint,
                    backEnabled = !lyricsOver,
                    onEditLyrics = { onEditLyrics(t) }, onLyricsFullscreen = { lyricsFull = true },
                )
            }
        }

        // ---- lyrics over the whole screen -------------------------------------------
        AnimatedVisibility(
            lyricsOver,
            enter = fadeIn(Specs.appear()) + scaleIn(Specs.enter(), initialScale = 0.96f),
            exit = fadeOut(Specs.disappear()) + scaleOut(Specs.leave(), targetScale = 0.98f),
        ) {
            controller?.let { LyricsFullscreen(t, it, ui, tint, onEdit = { onEditLyrics(t) }, onClose = { lyricsFull = false }) }
        }
    }
}
