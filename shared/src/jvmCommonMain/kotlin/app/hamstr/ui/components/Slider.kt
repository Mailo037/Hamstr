package app.hamstr.ui.components

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import app.hamstr.ui.theme.Hamstr
import kotlin.math.roundToInt

/**
 * A value on a line, in whole [steps] from [range]'s start to its end: the bar's track sinks
 * into its panel, the part up to the thumb is accent. Tapping jumps, dragging follows the
 * finger, each step ticks, and the thumb grows while held. [onDone] gets the value let go at.
 */
@Composable
fun HSlider(
    value: Int,
    range: IntRange,
    label: String,
    modifier: Modifier = Modifier,
    describe: (Int) -> String = { it.toString() },
    onDone: (Int) -> Unit = {},
    onChange: (Int) -> Unit,
) {
    val c = Hamstr.colors
    val haptics = LocalHapticFeedback.current
    val change by rememberUpdatedState(onChange)
    val done by rememberUpdatedState(onDone)
    val current by rememberUpdatedState(value)
    var held by remember { mutableStateOf(false) }
    val thumb by animateDpAsState(if (held) 11.dp else 8.dp, Specs.press(), label = "sliderThumb")
    val span = (range.last - range.first).coerceAtLeast(1)
    Canvas(
        modifier
            .fillMaxWidth()
            .height(48.dp)
            .semantics {
                contentDescription = label
                stateDescription = describe(value)
                setProgress { v -> change(v.roundToInt().coerceIn(range)); done(v.roundToInt().coerceIn(range)); true }
            }
            .pointerInput(range) {
                val edge = 12.dp.toPx()
                fun at(x: Float) = (range.first + ((x - edge) / (size.width - 2 * edge)).coerceIn(0f, 1f) * span).roundToInt()
                awaitEachGesture {
                    val down = awaitFirstDown()
                    down.consume()
                    held = true
                    var last = current
                    fun move(x: Float) {
                        val v = at(x)
                        if (v != last) {
                            last = v
                            haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
                            change(v)
                        }
                    }
                    move(down.position.x)
                    while (true) {
                        val event = awaitPointerEvent()
                        val p = event.changes.firstOrNull { it.id == down.id } ?: break
                        if (!p.pressed) break
                        p.consume()
                        move(p.position.x)
                    }
                    held = false
                    done(last)
                }
            },
    ) {
        val edge = 12.dp.toPx()
        val y = size.height / 2
        val x = edge + (size.width - 2 * edge) * ((value - range.first).toFloat() / span)
        drawLine(c.canvas, Offset(edge, y), Offset(size.width - edge, y), 4.dp.toPx(), StrokeCap.Round)
        if (value > range.first) drawLine(c.accent, Offset(edge, y), Offset(x, y), 4.dp.toPx(), StrokeCap.Round)
        drawCircle(if (value > range.first) c.accent else c.muted, thumb.toPx(), Offset(x, y))
    }
}
