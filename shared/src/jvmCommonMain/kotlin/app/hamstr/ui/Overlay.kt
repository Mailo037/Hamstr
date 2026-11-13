package app.hamstr.ui

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.backhandler.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.unit.dp
import app.hamstr.Platform
import app.hamstr.ui.components.rememberVelocityStretch
import kotlinx.coroutines.launch
import kotlin.math.abs
import app.hamstr.ui.components.Specs

/**
 * Dim backdrop plus a bottom sheet. The sheet springs in, can be dragged down (or flung)
 * to dismiss, rubber-bands and stretches upwards when pulled the other way, and squashes
 * slightly with its speed. On the desktop the same content opens as a modal in the middle
 * of the window instead (see [Modal]).
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun Overlay(visible: Boolean, onDismiss: () -> Unit, content: @Composable () -> Unit) {
    if (visible) BackHandler(onBack = onDismiss)
    if (Platform.desktop) return Modal(visible, onDismiss, content)
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
            enter = fadeIn(Specs.appear()),
            exit = slideOutVertically(Specs.leave()) { it } + fadeOut(Specs.listOut()),
        ) {
            // The way up, as a share of the sheet's height: 1 is below the screen, 0 in place. A
            // sheet is heavier than a page, so it comes up a little softer than Specs.enter; where
            // the spring overshoots, the sheet stretches up from the bottom edge instead of
            // lifting off it, so no gap opens under it.
            val rise = remember { Animatable(1f, visibilityThreshold = 0.0005f) }
            LaunchedEffect(Unit) { rise.animateTo(0f, spring(dampingRatio = 0.78f, stiffness = 360f)) }
            Box(
                Modifier
                    .onSizeChanged { height = it.height.coerceAtLeast(1) }
                    .graphicsLayer {
                        val o = offset.value
                        val r = rise.value
                        translationY = o.coerceAtLeast(0f) + r.coerceAtLeast(0f) * size.height
                        val up = (if (o < 0f) -o / height else 0f) + (if (r < 0f) -r else 0f)
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
                                    scope.launch { offset.animateTo(0f, Specs.release(), initialVelocity = v) }
                                }
                            },
                            onDragCancel = { scope.launch { offset.animateTo(0f, Specs.release()) } },
                        )
                    },
            ) { content() }
        }
    }
}

/**
 * The desktop's way to show a sheet: dimmed window, the content in its middle. It grows in
 * from a little smaller and leaves quicker than it came; a click beside it or Esc closes it.
 * It keeps clear of the window's title bar, which stays on top and usable.
 */
@Composable
private fun Modal(visible: Boolean, onDismiss: () -> Unit, content: @Composable () -> Unit) {
    Box(Modifier.fillMaxSize()) {
        AnimatedVisibility(visible, enter = fadeIn(Specs.blend()), exit = fadeOut(tween(150))) {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.55f))
                    .clickable(remember { MutableInteractionSource() }, null, onClick = onDismiss),
            )
        }
        AnimatedVisibility(
            visible,
            modifier = Modifier.align(Alignment.Center).padding(horizontal = 24.dp, vertical = 48.dp),
            enter = scaleIn(spring(dampingRatio = 0.78f, stiffness = 420f), initialScale = 0.94f) + fadeIn(Specs.appear()),
            exit = scaleOut(Specs.leave(150), targetScale = 0.97f) + fadeOut(Specs.listOut()),
        ) { content() }
    }
}
