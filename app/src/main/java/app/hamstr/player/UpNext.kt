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

/** What the drawer shows when pulled up. */
internal enum class DrawerTab { Queue, Lyrics }

/**
 * The song player's drawer on an upright phone, as in music apps: closed it is a grip with
 * two tabs at the foot of the player, the queue and the lyrics. Pulled up (or a tab tapped)
 * it covers the player, with the song shrunk to one row above it: its picture, title and
 * play. Pulled down, a tap on that row or Back put it away again. [open] runs from 0 (closed)
 * to 1; [closedTop] and [openTop] are where its top edge sits then, in pixels.
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
) {
    val c = Hamstr.colors
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()
    val haptics = LocalHapticFeedback.current
    val settle = spring<Float>(dampingRatio = 0.86f, stiffness = 420f)
    val p = open.value.coerceIn(0f, 1f)
    val travel = (closedTop - openTop).coerceAtLeast(1f)
    fun to(target: Float) {
        if ((target > 0.5f) != (open.value > 0.5f)) haptics.performHapticFeedback(HapticFeedbackType.GestureThresholdActivate)
        scope.launch { open.animateTo(target, settle) }
    }
    BackHandler(enabled = backEnabled && p > 0.5f) { to(0f) }
    // Up or down anywhere on the drawer's own parts moves it with the finger; let go, it
    // goes the way it was flung, or to the nearer end.
    val drag = Modifier.pointerInput(travel) {
        val tracker = VelocityTracker()
        var total = 0f
        detectVerticalDragGestures(
            onDragStart = { tracker.resetTracking(); total = 0f },
            onVerticalDrag = { change, dy ->
                change.consume()
                total += dy
                tracker.addPosition(change.uptimeMillis, Offset(0f, total))
                scope.launch { open.snapTo((open.value - dy / travel).coerceIn(0f, 1f)) }
            },
            onDragEnd = {
                val v = tracker.calculateVelocity().y
                to(when { v < -1200f -> 1f; v > 1200f -> 0f; open.value > 0.5f -> 1f; else -> 0f })
            },
            onDragCancel = { to(if (open.value > 0.5f) 1f else 0f) },
        )
    }
    val px = { v: Float -> with(density) { v.toDp() } }

    // The song in one row, where the top bar and the cover were.
    if (p > 0.01f) {
        Box(
            Modifier
                .width(px(width))
                .height(px(openTop))
                .graphicsLayer { alpha = ((p - 0.4f) / 0.6f).coerceIn(0f, 1f) }
                .background(c.canvas)
                .then(drag)
                .pressable({ to(0f) }, label = stringResource(R.string.player_now_playing)),
            contentAlignment = Alignment.BottomStart,
        ) {
            Column(Modifier.fillMaxWidth().padding(start = 20.dp, end = 12.dp, bottom = 10.dp)) {
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

    val top = lerp(closedTop, openTop, p)
    Column(
        Modifier
            .offset { IntOffset(0, top.roundToInt()) }
            .width(px(width))
            .height(px(height - top))
            .clip(RoundedCornerShape(topStart = 22.dp, topEnd = 22.dp))
            .background(c.overlay)
            .then(drag),
    ) {
        Box(Modifier.fillMaxWidth().padding(top = 8.dp), contentAlignment = Alignment.Center) {
            Box(Modifier.size(width = 32.dp, height = 4.dp).clip(CircleShape).background(c.line))
        }
        Row(Modifier.fillMaxWidth().height(DRAWER_CLOSED - 12.dp).padding(horizontal = 12.dp)) {
            listOf(DrawerTab.Queue to R.string.queue_title, DrawerTab.Lyrics to R.string.lyrics).forEach { (which, label) ->
                // The open tab is lit; closed, neither is.
                val lit = animateSelection(which == tab) * p
                Box(
                    Modifier.weight(1f).fillMaxSize().pressable({
                        onTab(which)
                        if (open.value < 0.5f) to(1f)
                    }, label = stringResource(label)),
                    contentAlignment = Alignment.Center,
                ) {
                    Txt(stringResource(label), style = Hamstr.type.bodyMedium, color = androidx.compose.ui.graphics.lerp(c.muted, c.ink, lit))
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
                .graphicsLayer { alpha = p }
                .padding(start = 12.dp, end = 12.dp, top = 8.dp, bottom = px(navBottom) + 8.dp),
        ) {
            when (tab) {
                DrawerTab.Queue -> QueueTab(controller, ui)
                DrawerTab.Lyrics -> Box(Modifier.fillMaxWidth().weight(1f).clip(RoundedCornerShape(16.dp))) {
                    LyricsPanel(t, controller, ui, onEdit = onEditLyrics, onFullscreen = onLyricsFullscreen)
                }
            }
        }
    }
}

/** The queue in the drawer: where it stands, shuffle and repeat, then its rows. */
@Composable
private fun androidx.compose.foundation.layout.ColumnScope.QueueTab(controller: Player, ui: PlayerUi) {
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
    QueueList(fill = true, onClose = {})
}
