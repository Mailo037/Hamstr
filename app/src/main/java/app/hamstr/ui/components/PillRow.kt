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
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * A row of options with one selection pill that can be tapped or dragged. The pill
 * stretches with its speed, overshoots a little on arrival, rubber-bands past the ends
 * and ticks (haptics) as it crosses into another option. Dragging commits on release.
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
    item: @Composable (index: Int, nearness: Float) -> Unit,
) {
    val scope = rememberCoroutineScope()
    val haptics = LocalHapticFeedback.current
    val reduce = LocalReduceMotion.current
    val select by rememberUpdatedState(onSelect)
    BoxWithConstraints(modifier) {
        val slot = constraints.maxWidth / count.toFloat()
        val slotDp = with(LocalDensity.current) { slot.toDp() }
        val x = remember { Animatable(selected * slot) }
        var dragging by remember { mutableStateOf(false) }
        var raw by remember { mutableFloatStateOf(0f) }
        var lastSlot by remember { mutableIntStateOf(selected) }
        val stretch by rememberVelocityStretch({ x.value }, sensitivity = 0.00011f, max = 0.3f)
        val arrive = spring<Float>(dampingRatio = 0.62f, stiffness = 520f)

        LaunchedEffect(selected, slot) {
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
                            dragging = false
                            val i = (x.value / slot).roundToInt().coerceIn(0, count - 1)
                            select(i)
                            scope.launch { x.animateTo(i * slot, arrive) }
                        },
                        onDragCancel = {
                            dragging = false
                            scope.launch { x.animateTo(selected * slot, arrive) }
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
