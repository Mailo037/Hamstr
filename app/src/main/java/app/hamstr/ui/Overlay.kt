package app.hamstr.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import app.hamstr.ui.components.rememberVelocityStretch
import kotlinx.coroutines.launch
import kotlin.math.abs
import app.hamstr.ui.components.Specs

/**
 * Dim backdrop plus a bottom sheet. The sheet springs in, can be dragged down (or flung)
 * to dismiss, rubber-bands and stretches upwards when pulled the other way, and squashes
 * slightly with its speed.
 */
@Composable
fun Overlay(visible: Boolean, onDismiss: () -> Unit, content: @Composable () -> Unit) {
    if (visible) BackHandler(onBack = onDismiss)
    val dismiss by rememberUpdatedState(onDismiss)
    val scope = rememberCoroutineScope()
    val offset = remember { Animatable(0f) }
    var height by remember { mutableIntStateOf(1) }
    var raw by remember { mutableFloatStateOf(0f) }
    val stretch by rememberVelocityStretch({ offset.value }, sensitivity = 0.000025f, max = 0.05f)
    LaunchedEffect(visible) { if (visible) offset.snapTo(0f) }

    Box(Modifier.fillMaxSize()) {
        AnimatedVisibility(visible, enter = fadeIn(Specs.blend()), exit = fadeOut(tween(150))) {
            Box(
                Modifier
                    .fillMaxSize()
                    .graphicsLayer { alpha = 1f - (offset.value / height).coerceIn(0f, 1f) * 0.8f }
                    .background(Color.Black.copy(alpha = 0.55f))
                    .clickable(remember { MutableInteractionSource() }, null, onClick = onDismiss),
            )
        }
        AnimatedVisibility(
            visible,
            modifier = Modifier.align(Alignment.BottomCenter),
            // A sheet is heavier than a page: it comes up a little softer than Specs.enter.
            enter = slideInVertically(spring(dampingRatio = 0.78f, stiffness = 360f)) { it } + fadeIn(Specs.appear()),
            exit = slideOutVertically(Specs.leave()) { it } + fadeOut(Specs.listOut()),
        ) {
            Box(
                Modifier
                    .onSizeChanged { height = it.height.coerceAtLeast(1) }
                    .graphicsLayer {
                        val o = offset.value
                        translationY = o.coerceAtLeast(0f)
                        val up = if (o < 0f) -o / height else 0f
                        scaleY = 1f + up + abs(stretch)
                        scaleX = 1f - abs(stretch) * 0.3f
                        transformOrigin = TransformOrigin(0.5f, 1f)
                    }
                    .pointerInput(Unit) {
                        val tracker = VelocityTracker()
                        detectVerticalDragGestures(
                            onDragStart = { tracker.resetTracking(); raw = offset.value },
                            onVerticalDrag = { change, dy ->
                                change.consume()
                                raw += dy
                                tracker.addPosition(change.uptimeMillis, Offset(0f, raw))
                                // Downwards it follows the finger; upwards it resists hard.
                                val shown = if (raw >= 0f) raw else -height * 0.06f * (1f - 1f / (1f - raw / (height * 0.25f)))
                                scope.launch { offset.snapTo(shown) }
                            },
                            onDragEnd = {
                                val v = tracker.calculateVelocity().y
                                if (offset.value > height * 0.28f || v > 1600f) {
                                    scope.launch {
                                        offset.animateTo(height.toFloat(), Specs.leave(170), initialVelocity = v)
                                        dismiss()
                                    }
                                } else {
                                    scope.launch { offset.animateTo(0f, Specs.settle(), initialVelocity = v) }
                                }
                            },
                            onDragCancel = { scope.launch { offset.animateTo(0f, Specs.settle()) } },
                        )
                    },
            ) { content() }
        }
    }
}
