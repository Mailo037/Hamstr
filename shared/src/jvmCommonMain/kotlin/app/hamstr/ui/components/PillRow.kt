package app.hamstr.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * A row of options with one selection pill that can be tapped or dragged. The pill
 * stretches with its speed, overshoots a little on arrival, rubber-bands past the ends
 * and ticks (haptics) as it crosses into another option. Dragging commits on release.
 *
 * With [position] (the option the pill stands on, fractional while it moves, such as a
 * pager's page plus its offset) the pill follows that instead of springing on its own, so
 * it moves with a swipe and lands with the page. Only its own drag takes it over.
 */
@Composable
fun DragPillRow(
    count: Int,
    selected: Int,
    onSelect: (Int) -> Unit,
    itemHeight: Dp,
    pillRadius: Dp,
    pillColor: Color,
    modifier: Modifier = Modifier,
    position: (() -> Float)? = null,
    item: @Composable (index: Int, nearness: Float) -> Unit,
) {
    val scope = rememberCoroutineScope()
    val haptics = LocalHapticFeedback.current
    val reduce = LocalReduceMotion.current
    val select by rememberUpdatedState(onSelect)
    val follow by rememberUpdatedState(position)
    // The drag handler outlives a recomposition; it reads the selection as it is now.
    val current by rememberUpdatedState(selected)
    BoxWithConstraints(modifier) {
        val slot = constraints.maxWidth / count.toFloat()
        val slotDp = with(LocalDensity.current) { slot.toDp() }
        val x = remember { Animatable(selected * slot) }
        var dragging by remember { mutableStateOf(false) }
        // After a drag of its own, the option the pill springs to; the followed position takes
        // over again once it has got there too (not observed, so clearing it moves nothing).
        val handoff = remember { intArrayOf(-1) }
        var handoffs by remember { mutableIntStateOf(0) }
        var raw by remember { mutableFloatStateOf(0f) }
        var lastSlot by remember { mutableIntStateOf(selected) }
        val stretch by rememberVelocityStretch({ x.value }, sensitivity = 0.00011f, max = 0.3f)
        val arrive = Specs.arrive<Float>()

        if (position != null) {
            LaunchedEffect(slot) {
                snapshotFlow { if (dragging) null else follow?.invoke() }.collect { p ->
                    if (p == null) return@collect
                    val h = handoff[0]
                    if (h >= 0) {
                        if (abs(p - h) < 0.001f) handoff[0] = -1
                        return@collect
                    }
                    lastSlot = p.roundToInt().coerceIn(0, count - 1)
                    x.snapTo(p * slot)
                }
            }
            // Should the position never get there (swiped elsewhere meanwhile), the pill follows it again.
            LaunchedEffect(handoffs) {
                if (handoffs == 0) return@LaunchedEffect
                delay(1_000)
                if (handoff[0] >= 0 && !dragging) {
                    handoff[0] = -1
                    follow?.invoke()?.let { x.snapTo(it * slot) }
                }
            }
        } else LaunchedEffect(selected, slot) {
            if (!dragging) if (reduce) x.snapTo(selected * slot) else x.animateTo(selected * slot, arrive)
        }

        Box(
            Modifier
                .offset { IntOffset(x.value.roundToInt(), 0) }
                .width(slotDp)
                .height(itemHeight)
                .stretchX { stretch }
                .clip(RoundedCornerShape(pillRadius))
                .background(pillColor),
        )
        Row(
            Modifier
                .fillMaxWidth()
                .pointerInput(count, slot) {
                    val maxX = slot * (count - 1)
                    detectHorizontalDragGestures(
                        onDragStart = { start ->
                            dragging = true
                            // Grab the pill where it is, or pull it under the finger.
                            raw = if (start.x in x.value..(x.value + slot)) x.value else (start.x - slot / 2f).coerceIn(0f, maxX)
                            scope.launch { x.animateTo(raw, spring(stiffness = 900f)) }
                        },
                        onHorizontalDrag = { change, dx ->
                            change.consume()
                            raw += dx
                            // Past either end the pill follows ever slower, at most 20 % of a slot.
                            fun band(over: Float) = slot * 0.2f * (1f - 1f / (1f + over / slot * 2f))
                            val shown = when {
                                raw < 0f -> -band(-raw)
                                raw > maxX -> maxX + band(raw - maxX)
                                else -> raw
                            }
                            scope.launch { x.snapTo(shown) }
                            val now = (shown / slot).roundToInt().coerceIn(0, count - 1)
                            if (now != lastSlot) {
                                lastSlot = now
                                haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
                            }
                        },
                        onDragEnd = {
                            val i = (x.value / slot).roundToInt().coerceIn(0, count - 1)
                            if (position != null) { handoff[0] = i; handoffs++ }
                            dragging = false
                            select(i)
                            scope.launch { if (reduce) x.snapTo(i * slot) else x.animateTo(i * slot, arrive) }
                        },
                        onDragCancel = {
                            if (position != null) { handoff[0] = current; handoffs++ }
                            dragging = false
                            scope.launch { if (reduce) x.snapTo(current * slot) else x.animateTo(current * slot, arrive) }
                        },
                    )
                },
        ) {
            repeat(count) { i ->
                val near = (1f - abs(x.value / slot - i)).coerceIn(0f, 1f)
                Box(
                    Modifier
                        .weight(1f)
                        .height(itemHeight)
                        .clickable(remember { MutableInteractionSource() }, null, role = Role.Tab) {
                            if (i != selected) haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
                            lastSlot = i
                            select(i)
                        },
                    contentAlignment = Alignment.Center,
                ) { item(i, near) }
            }
        }
    }
}
