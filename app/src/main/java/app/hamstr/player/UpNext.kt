package app.hamstr.player

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.unit.Velocity
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.lerp
import androidx.compose.ui.graphics.lerp
import kotlin.math.abs
import androidx.media3.common.Player
import app.hamstr.R
import app.hamstr.data.DownloadTask
import app.hamstr.data.Lyrics
import app.hamstr.res.*
import app.hamstr.ui.QueueList
import app.hamstr.ui.components.AsciiImage
import app.hamstr.ui.components.IconButton
import app.hamstr.ui.components.ProgressLine
import app.hamstr.ui.components.Txt
import app.hamstr.ui.components.animateSelection
import app.hamstr.ui.components.pressable
import app.hamstr.ui.rememberImage
import app.hamstr.ui.theme.Hamstr
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/** How tall the closed drawer is above the navigation bar: its grip and its tabs. */
internal val DRAWER_CLOSED = 56.dp

/**
 * The drawer's half stop as a share of the way up from [closedTop] to [openTop], for a top
 * edge at [halfTop]; null where either half would leave less than [minRoom] (half of it above).
 */
internal fun drawerHalf(closedTop: Float, openTop: Float, halfTop: Float, minRoom: Float): Float? =
    if (halfTop < closedTop - minRoom && halfTop > openTop + minRoom / 2) (closedTop - halfTop) / (closedTop - openTop).coerceAtLeast(1f) else null

/** What the drawer shows when pulled up. */
internal enum class DrawerTab { Queue, Lyrics }

/**
 * The song player's drawer on an upright phone, as in music apps: closed it is a grip with
 * two tabs at the foot of the player, the queue and the lyrics. It stops twice on the way up:
 * half open its top sits under the play buttons, so the song stays in sight and in reach above
 * the list; all the way up it covers the player, with the song shrunk to one row above it (its
 * picture, title and play). A tab tapped while closed opens it halfway; a tap on the song's
 * row, Back or a pull down put it away again. [open] runs from 0 (closed) to 1 (full);
 * [closedTop], [halfTop] and [openTop] are where its top edge sits then, in pixels. Without
 * room for a useful half ([halfTop] null or too close to either end) it has two stops. It and
 * the song's row take the cover's [tint].
 */
@Composable
internal fun UpNextDrawer(
    t: DownloadTask,
    controller: Player,
    ui: PlayerUi,
    open: Animatable<Float, AnimationVector1D>,
    tab: DrawerTab,
    onTab: (DrawerTab) -> Unit,
    width: Float,
    height: Float,
    closedTop: Float,
    openTop: Float,
    navBottom: Float,
    backEnabled: Boolean,
    onEditLyrics: () -> Unit,
    onLyricsFullscreen: () -> Unit,
    halfTop: Float? = null,
    tint: Color = Hamstr.colors.overlay,
) {
    val c = Hamstr.colors
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()
    val haptics = LocalHapticFeedback.current
    val settle = spring<Float>(dampingRatio = 0.86f, stiffness = 420f)
    val p = open.value.coerceIn(0f, 1f)
    val travel = (closedTop - openTop).coerceAtLeast(1f)
    val half = halfTop?.let { drawerHalf(closedTop, openTop, it, with(density) { 160.dp.toPx() }) }
    val stops = listOfNotNull(0f, half, 1f)
    val firstStop = half ?: 1f
    // From the half stop up: how far towards full it is (the song's row comes in on that leg).
    val upper = if (half == null) p else ((p - half) / (1f - half)).coerceIn(0f, 1f)
    fun to(target: Float) {
        if (abs(target - open.value) > 0.02f) haptics.performHapticFeedback(HapticFeedbackType.GestureThresholdActivate)
        scope.launch { open.animateTo(target, settle) }
    }
    BackHandler(enabled = backEnabled && p > firstStop / 2f) { to(0f) }
    // Up or down anywhere on the drawer's own parts moves it with the finger; let go, it goes
    // one stop on from [from] the way it was flung, or to the nearest one.
    val currentStops by rememberUpdatedState(stops)
    fun release(v: Float, from: Float) {
        val s = currentStops
        to(
            when {
                v < -1200f -> s.firstOrNull { it > from + 0.01f } ?: s.last()
                v > 1200f -> s.lastOrNull { it < from - 0.01f } ?: s.first()
                else -> s.minBy { abs(it - open.value) }
            },
        )
    }
    // The list (and the lyrics) move the drawer too, as a sheet does: pushed up, it first opens
    // all the way, then scrolls; pulled down from its top, it comes down with the finger.
    val nested = remember(travel) {
        object : NestedScrollConnection {
            fun move(dy: Float): Float {
                val before = open.value
                val next = (before - dy / travel).coerceIn(0f, 1f)
                scope.launch { open.snapTo(next) }
                return (before - next) * travel
            }
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset =
                if (source == NestedScrollSource.UserInput && available.y < 0f && open.value < 1f) Offset(0f, move(available.y)) else Offset.Zero
            override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset =
                if (source == NestedScrollSource.UserInput && available.y > 0f && open.value > 0f) Offset(0f, move(available.y)) else Offset.Zero
            override suspend fun onPreFling(available: Velocity): Velocity {
                // Left between two stops, it settles; at a stop the list keeps the fling.
                if (currentStops.all { abs(it - open.value) > 0.004f }) {
                    release(available.y, open.value)
                    return available
                }
                return Velocity.Zero
            }
        }
    }
    val drag = Modifier.pointerInput(travel) {
        val tracker = VelocityTracker()
        var total = 0f
        var from = 0f
        detectVerticalDragGestures(
            onDragStart = { tracker.resetTracking(); total = 0f; from = open.value },
            onVerticalDrag = { change, dy ->
                change.consume()
                total += dy
                tracker.addPosition(change.uptimeMillis, Offset(0f, total))
                scope.launch { open.snapTo((open.value - dy / travel).coerceIn(0f, 1f)) }
            },
            onDragEnd = { release(tracker.calculateVelocity().y, from) },
            onDragCancel = { to(currentStops.minBy { abs(it - open.value) }) },
        )
    }
    val px = { v: Float -> with(density) { v.toDp() } }
    val sheet = lerp(c.overlay, tint, 0.28f)
    val head = lerp(c.canvas, tint, 0.38f)

    // The song in one row, where the top bar and the cover were.
    if (upper > 0.01f) {
        Box(
            Modifier
                .width(px(width))
                .height(px(openTop))
                // The ground comes first and hides the top bar under it; the row follows.
                .background(head.copy(alpha = (upper * 2.5f).coerceIn(0f, 1f)))
                .then(drag)
                .pressable({ to(0f) }, label = stringResource(R.string.player_now_playing)),
            contentAlignment = Alignment.BottomStart,
        ) {
            Column(
                Modifier.fillMaxWidth().graphicsLayer { alpha = ((upper - 0.4f) / 0.6f).coerceIn(0f, 1f) }
                    .padding(start = 20.dp, end = 12.dp, bottom = 10.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(44.dp).clip(RoundedCornerShape(10.dp)).background(c.black)) {
                        AsciiImage(rememberImage(t.thumbnail, 160), reveal = 1f, cell = 4.dp, modifier = Modifier.size(44.dp))
                    }
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Txt(t.title, style = Hamstr.type.bodyMedium, maxLines = 1)
                        (t.uploader?.let(Lyrics::cleanArtist) ?: t.source)?.let { Txt(it, style = Hamstr.type.small, color = c.muted, maxLines = 1) }
                    }
                    PlayPause(ui.isPlaying, size = 40.dp, onClick = { if (controller.isPlaying) controller.pause() else controller.play() })
                    IconButton(Res.drawable.ic_expand_more, stringResource(R.string.player_minimize), { to(0f) }, tint = c.muted, background = Color.Transparent)
                }
                Spacer(Modifier.height(8.dp))
                ProgressLine(if (ui.duration > 0) ui.position / ui.duration.toFloat() else 0f)
            }
        }
    }

    // Up to the first stop the drawer's contents come in; past it they are all there.
    val shown = (p / firstStop).coerceIn(0f, 1f)
    val top = lerp(closedTop, openTop, p)
    Column(
        Modifier
            .offset { IntOffset(0, top.roundToInt()) }
            .width(px(width))
            .height(px(height - top))
            .clip(RoundedCornerShape(topStart = 22.dp, topEnd = 22.dp))
            .background(lerp(c.overlay, sheet, shown))
            .then(drag),
    ) {
        Box(Modifier.fillMaxWidth().padding(top = 8.dp), contentAlignment = Alignment.Center) {
            Box(Modifier.size(width = 32.dp, height = 4.dp).clip(CircleShape).background(c.line))
        }
        Row(Modifier.fillMaxWidth().height(DRAWER_CLOSED - 12.dp).padding(horizontal = 12.dp)) {
            listOf(DrawerTab.Queue to R.string.queue_title, DrawerTab.Lyrics to R.string.lyrics).forEach { (which, label) ->
                // The open tab is lit; closed, neither is.
                val lit = animateSelection(which == tab) * shown
                Box(
                    Modifier.weight(1f).fillMaxSize().pressable({
                        onTab(which)
                        if (open.value < firstStop / 2f) to(firstStop)
                    }, label = stringResource(label)),
                    contentAlignment = Alignment.Center,
                ) {
                    Txt(stringResource(label), style = Hamstr.type.bodyMedium, color = lerp(c.muted, c.ink, lit))
                    Box(
                        Modifier.align(Alignment.BottomCenter).size(width = 40.dp, height = 2.dp)
                            .graphicsLayer { alpha = lit }.clip(CircleShape).background(c.accent),
                    )
                }
            }
        }
        if (p > 0.01f) Column(
            Modifier
                .fillMaxWidth()
                .weight(1f)
                .graphicsLayer { alpha = shown }
                .nestedScroll(nested)
                .padding(start = 12.dp, end = 12.dp, top = 8.dp, bottom = px(navBottom) + 8.dp),
        ) {
            when (tab) {
                DrawerTab.Queue -> QueueTab(controller, ui, tint)
                DrawerTab.Lyrics -> Box(Modifier.fillMaxWidth().weight(1f).clip(RoundedCornerShape(16.dp))) {
                    LyricsPanel(t, controller, ui, onEdit = onEditLyrics, onFullscreen = onLyricsFullscreen)
                }
            }
        }
    }
}

/** The queue in the drawer: where it stands, shuffle and repeat, then its rows. */
@Composable
private fun androidx.compose.foundation.layout.ColumnScope.QueueTab(controller: Player, ui: PlayerUi, tint: Color) {
    val c = Hamstr.colors
    val haptics = LocalHapticFeedback.current
    val queue by Playback.queue.collectAsState()
    val current by Playback.current.collectAsState()
    val at = queue.indexOfFirst { it.id == current?.id }
    Row(Modifier.fillMaxWidth().padding(start = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        Txt(
            if (queue.size > 1) stringResource(R.string.queue_position, at + 1, queue.size) else pluralStringResource(R.plurals.lib_files, queue.size, queue.size),
            style = Hamstr.type.mono, color = c.muted, modifier = Modifier.weight(1f),
        )
        ModeButton(Res.drawable.ic_shuffle, stringResource(R.string.player_shuffle), on = ui.shuffle, enabled = queue.size > 1) {
            haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
            controller.shuffleModeEnabled = !ui.shuffle
        }
        RepeatButton(controller, ui, queue.size)
    }
    Spacer(Modifier.height(4.dp))
    // Rows lie on the tinted drawer; the song that plays is lit in the cover's colour.
    QueueList(fill = true, onClose = {}, rowColor = Color.Transparent, nowColor = lerp(c.surface, tint, 0.55f))
}
