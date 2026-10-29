package app.hamstr.ui.components

import org.jetbrains.compose.resources.DrawableResource
import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.dp
import app.hamstr.ui.theme.Hamstr
import app.hamstr.ui.theme.Radii
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.sign

data class SwipeAction(
    val icon: DrawableResource,
    val label: String,
    val color: Color,
    /** The row flies out instead of springing back (it is about to leave the list). */
    val removes: Boolean = false,
    val onTrigger: () -> Unit,
)

/**
 * Swipe a row sideways to act. The row tilts and squashes with the drag speed, the
 * action underneath grows until it arms (with a haptic tick), and past that point the
 * drag rubber-bands. Releasing armed runs the action.
 *
 * While it moves, the row carries [background] (what is behind it at rest), so it slides
 * over the action like a card instead of showing it through; the action's name only
 * shows once there is room for all of it.
 */
@Composable
fun SwipeRow(
    start: SwipeAction?,
    end: SwipeAction?,
    modifier: Modifier = Modifier,
    background: Color = Hamstr.colors.canvas,
    content: @Composable () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val haptics = LocalHapticFeedback.current
    val startAction by rememberUpdatedState(start)
    val endAction by rememberUpdatedState(end)
    val offset = remember { Animatable(0f) }
    var width by remember { mutableIntStateOf(1) }
    var raw by remember { mutableFloatStateOf(0f) }
    var armed by remember { mutableStateOf(false) }
    val stretch by rememberVelocityStretch({ offset.value }, sensitivity = 0.00006f, max = 0.12f)
    val pop = remember { Animatable(0f) }
    val back = Specs.settle<Float>()
    var labelWidth by remember { mutableIntStateOf(0) }
    // The edge padding, the icon and the gap before the name, plus a little air.
    val room = with(LocalDensity.current) { 60.dp.toPx() }
    val fade = with(LocalDensity.current) { 20.dp.toPx() }

    Box(modifier.onSizeChanged { width = it.width }) {
        val o = offset.value
        val threshold = width * 0.26f
        val action = when {
            o > 0f -> start
            o < 0f -> end
            else -> null
        }
        if (action != null) {
            val progress = (abs(o) / threshold).coerceIn(0f, 1f)
            Box(
                Modifier
                    .matchParentSize()
                    .clip(RoundedCornerShape(Radii.row))
                    .background(lerp(Hamstr.colors.overlay, action.color.copy(alpha = 0.28f), progress)),
                contentAlignment = if (o > 0f) Alignment.CenterStart else Alignment.CenterEnd,
            ) {
                Row(
                    Modifier
                        .padding(horizontal = 22.dp)
                        .graphicsLayer {
                            val s = 0.55f + 0.45f * progress + pop.value * 0.25f
                            scaleX = s; scaleY = s
                            alpha = progress
                        },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    val label = Modifier
                        .onSizeChanged { labelWidth = it.width }
                        .graphicsLayer { alpha = ((abs(offset.value) - room - labelWidth) / fade).coerceIn(0f, 1f) }
                    if (o < 0f) {
                        Txt(action.label, style = Hamstr.type.small, color = action.color, maxLines = 1, modifier = label)
                        Spacer(Modifier.width(8.dp))
                    }
                    Icon(action.icon, tint = action.color, size = 22.dp)
                    if (o > 0f) {
                        Spacer(Modifier.width(8.dp))
                        Txt(action.label, style = Hamstr.type.small, color = action.color, maxLines = 1, modifier = label)
                    }
                }
            }
        }
        Box(
            Modifier
                .graphicsLayer {
                    translationX = offset.value
                    val s = stretch
                    rotationZ = s * 14f
                    scaleY = 1f - abs(s) * 0.5f
                }
                .drawBehind { if (offset.value != 0f) drawRoundRect(background, cornerRadius = CornerRadius(Radii.row.toPx())) }
                .pointerInput(Unit) {
                    detectHorizontalDragGestures(
                        onDragStart = { raw = offset.value; scope.launch { offset.stop() } },
                        onHorizontalDrag = { change, dx ->
                            change.consume()
                            raw += dx
                            val dir = if (raw > 0f) startAction else endAction
                            val limit = width * 0.26f
                            val shown = if (dir == null) raw * 0.12f else rubberBand(raw, limit * 1.15f, 0.5f)
                            scope.launch { offset.snapTo(shown) }
                            val nowArmed = dir != null && abs(shown) >= limit
                            if (nowArmed != armed) {
                                armed = nowArmed
                                haptics.performHapticFeedback(if (nowArmed) HapticFeedbackType.GestureThresholdActivate else HapticFeedbackType.SegmentTick)
                                if (nowArmed) scope.launch { pop.snapTo(1f); pop.animateTo(0f, Specs.pop()) }
                            }
                        },
                        onDragEnd = {
                            val act = if (offset.value > 0f) startAction else endAction
                            val fire = armed && act != null
                            armed = false
                            if (fire && act != null) {
                                haptics.performHapticFeedback(HapticFeedbackType.Confirm)
                                if (act.removes) scope.launch {
                                    offset.animateTo(sign(offset.value) * width * 1.15f, Specs.leave(180))
                                    act.onTrigger()
                                    offset.snapTo(0f)
                                } else {
                                    act.onTrigger()
                                    scope.launch { offset.animateTo(0f, back) }
                                }
                            } else scope.launch { offset.animateTo(0f, back) }
                        },
                        onDragCancel = { armed = false; scope.launch { offset.animateTo(0f, back) } },
                    )
                },
        ) { content() }
    }
}
