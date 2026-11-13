package app.hamstr.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.ScrollbarStyle
import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.gestures.ScrollableState
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import app.hamstr.ui.theme.Hamstr
import kotlinx.coroutines.delay

@Composable
actual fun ScrollIndicator(state: LazyListState, modifier: Modifier) {
    Fading(state, modifier) { m, source -> VerticalScrollbar(rememberScrollbarAdapter(state), modifier = m, style = barStyle(), interactionSource = source) }
}

@Composable
actual fun ScrollIndicator(state: ScrollState, modifier: Modifier) {
    Fading(state, modifier) { m, source -> VerticalScrollbar(rememberScrollbarAdapter(state), modifier = m, style = barStyle(), interactionSource = source) }
}

/**
 * Calm when idle: the bar fades in while the list scrolls or the pointer is on it or drags it,
 * and out a moment after, so it doesn't flicker between the wheel's ticks. A list that fits has
 * no bar at all.
 */
@Composable
private fun Fading(state: ScrollableState, modifier: Modifier, bar: @Composable (Modifier, MutableInteractionSource) -> Unit) {
    if (!state.canScrollForward && !state.canScrollBackward) return
    val source = remember { MutableInteractionSource() }
    val hovered by source.collectIsHoveredAsState()
    val dragged by source.collectIsDraggedAsState()
    val active = state.isScrollInProgress || hovered || dragged
    var shown by remember { mutableStateOf(false) }
    LaunchedEffect(active) {
        if (!active) delay(900)
        shown = active
    }
    val reduce = LocalReduceMotion.current
    val alpha by animateFloatAsState(
        if (shown) 1f else 0f,
        if (reduce) snap<Float>() else tween<Float>(if (shown) 160 else 240),
        label = "scrollbar",
    )
    bar(modifier.fillMaxHeight().graphicsLayer { this.alpha = alpha }, source)
}

// 6 dp with half-round ends; it rests in `hover` and lights to `ghost` under the pointer.
@Composable
private fun barStyle(): ScrollbarStyle {
    val c = Hamstr.colors
    return ScrollbarStyle(
        minimalHeight = 40.dp,
        thickness = 6.dp,
        shape = RoundedCornerShape(3.dp),
        hoverDurationMillis = 160,
        unhoverColor = c.hover,
        hoverColor = c.ghost,
    )
}
