package app.hamstr.ui.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.hamstr.player.AbLoop
import app.hamstr.res.*
import app.hamstr.ui.formatDuration
import app.hamstr.ui.theme.Hamstr
import org.jetbrains.compose.resources.stringResource
import kotlin.math.abs

/** A hold on a seek bar in progress: where it started and where the pointer is now (the same until it slides), 0..1. */
data class LoopMark(val from: Float, val to: Float) {
    /** What [loop] becomes when the hold is let go here. */
    fun applied(loop: AbLoop?, duration: Long) = AbLoop.marked(loop, (from * duration).toLong(), (to * duration).toLong(), duration)
}

/** The hold that [loopHold] tracks, for the bar to draw what it would make ([shown]). */
@Stable
class LoopHold {
    var mark by mutableStateOf<LoopMark?>(null)
        internal set

    /** The loop as it would be if the hold ended now; [loop] itself without a hold. */
    fun shown(loop: AbLoop?, duration: Long): AbLoop? = mark?.applied(loop, duration) ?: loop
}

@Composable
fun rememberLoopHold() = remember { LoopHold() }

/**
 * Marks an A-B loop on a seek bar by holding it ([AbLoop.marked]): hold once for A and again
 * for B, or hold and slide over the stretch. [onLoop] gets the new loop when the hold is let
 * go. Screen readers get the same as actions at [position]. Put it after the bar's own tap
 * and drag handlers, so a hold takes over from them.
 */
@Composable
fun Modifier.loopHold(hold: LoopHold, loop: AbLoop?, duration: () -> Long, position: () -> Long, onLoop: (AbLoop?) -> Unit): Modifier {
    val haptics = LocalHapticFeedback.current
    val current by rememberUpdatedState(loop)
    val length by rememberUpdatedState(duration)
    val set by rememberUpdatedState(onLoop)
    val fromHere = stringResource(Res.string.loop_from_here)
    val toHere = stringResource(Res.string.loop_to_here)
    val off = stringResource(Res.string.loop_off)
    return this
        .semantics {
            val at = { AbLoop.marked(loop, position(), position(), duration()) }
            customActions = listOfNotNull(
                if (loop == null || loop.looping) CustomAccessibilityAction(fromHere) { onLoop(at()); true } else null,
                if (loop != null && !loop.looping) CustomAccessibilityAction(toHere) { onLoop(at()); true } else null,
                if (loop != null) CustomAccessibilityAction(off) { onLoop(null); true } else null,
            )
        }
        .pointerInput(hold) {
            var slid = false
            detectDragGesturesAfterLongPress(
                onDragStart = { at ->
                    if (length() <= 0) return@detectDragGesturesAfterLongPress
                    slid = false
                    val f = (at.x / size.width).coerceIn(0f, 1f)
                    hold.mark = LoopMark(f, f)
                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                },
                onDrag = { change, _ ->
                    val m = hold.mark ?: return@detectDragGesturesAfterLongPress
                    val x = change.position.x
                    slid = slid || abs(x - m.from * size.width) > viewConfiguration.touchSlop
                    if (slid) hold.mark = m.copy(to = (x / size.width).coerceIn(0f, 1f))
                },
                onDragEnd = {
                    val m = hold.mark
                    hold.mark = null
                    if (m != null && length() > 0) {
                        val next = m.applied(current, length())
                        haptics.performHapticFeedback(if (next.looping) HapticFeedbackType.Confirm else HapticFeedbackType.SegmentTick)
                        set(next)
                    }
                },
                onDragCancel = { hold.mark = null },
            )
        }
}

/**
 * An A-B loop drawn behind a seek bar's track, filling its box: a band over the stretch with
 * a tick at each end, or only A's tick while B is missing. The marks fade in and out.
 */
@Composable
fun LoopBand(loop: AbLoop?, duration: Long, modifier: Modifier = Modifier) {
    val c = Hamstr.colors
    val reduce = LocalReduceMotion.current
    // The last A and B, so the marks fade out where they were.
    val kept = remember { LongArray(2) }
    loop?.let { kept[0] = it.a; it.b?.let { b -> kept[1] = b } }
    val a by animateFloatAsState(if (loop != null) 1f else 0f, if (reduce) snap() else if (loop != null) Specs.appear() else Specs.disappear(), label = "loopA")
    val b by animateFloatAsState(if (loop?.b != null) 1f else 0f, if (reduce) snap() else if (loop?.b != null) Specs.appear() else Specs.disappear(), label = "loopB")
    if (duration <= 0 || a + b == 0f) return
    Canvas(modifier) {
        val cy = size.height / 2
        val ax = kept[0] / duration.toFloat() * size.width
        val bx = kept[1] / duration.toFloat() * size.width
        val tickW = 2.dp.toPx()
        val tickH = 16.dp.toPx()
        fun tick(x: Float, alpha: Float) = drawRoundRect(
            c.accent.copy(alpha = alpha), Offset(x - tickW / 2, cy - tickH / 2), Size(tickW, tickH), CornerRadius(tickW / 2),
        )
        if (b > 0f) {
            val h = 12.dp.toPx()
            drawRoundRect(c.accent.copy(alpha = 0.22f * b), Offset(minOf(ax, bx), cy - h / 2), Size(abs(bx - ax), h), CornerRadius(h / 2))
            tick(bx, b)
        }
        tick(ax, a)
    }
}

/**
 * Under a seek bar, between the times: how to mark B while only A is set, and once it
 * loops the stretch it plays. A tap on either ends the loop.
 */
@Composable
fun LoopPill(loop: AbLoop?, onClear: () -> Unit, modifier: Modifier = Modifier) {
    val c = Hamstr.colors
    val haptics = LocalHapticFeedback.current
    val off = stringResource(Res.string.loop_off)
    val clear = { haptics.performHapticFeedback(HapticFeedbackType.ToggleOff); onClear() }
    AnimatedContent(
        loop,
        modifier = modifier,
        transitionSpec = { fadeIn(Specs.appear()) togetherWith fadeOut(Specs.disappear()) },
        contentAlignment = Alignment.Center,
        label = "loopPill",
    ) { l ->
        val b = l?.b
        when {
            l == null -> Box(Modifier)
            b == null -> Txt(
                stringResource(Res.string.loop_hint), style = Hamstr.type.small, color = c.accent, maxLines = 1,
                modifier = Modifier.clip(RoundedCornerShape(8.dp)).pressable(clear, label = off).padding(horizontal = 6.dp),
            )
            else -> Row(
                Modifier.clip(RoundedCornerShape(8.dp)).background(c.accent.copy(alpha = 0.18f)).pressable(clear, label = off).padding(horizontal = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Icon(Res.drawable.ic_repeat, tint = c.accent, size = 14.dp)
                Txt("${formatDuration((l.a / 1000).toInt())}–${formatDuration((b / 1000).toInt())}", style = Hamstr.type.mono, color = c.accent, maxLines = 1)
                Icon(Res.drawable.ic_close, tint = c.accent, size = 12.dp)
            }
        }
    }
}
