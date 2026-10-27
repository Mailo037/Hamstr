package app.hamstr.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.ScrollableState
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp

/**
 * Fades out the edges a scroll container can still move towards, so content runs out
 * softly instead of being cut off. An edge with nothing behind it stays crisp; its fade
 * grows in as soon as there is.
 *
 * [insets] are the paddings that floating chrome (status bar, tab bar) lies over: content
 * scrolling under them disappears and the fade starts at their inner edge.
 */
fun Modifier.edgeFade(
    state: ScrollableState,
    orientation: Orientation,
    length: Dp = 28.dp,
    insets: PaddingValues = PaddingValues(0.dp),
): Modifier = composed {
    val back by animateFloatAsState(if (state.canScrollBackward) 1f else 0f, tween(180), label = "fadeBack")
    val forward by animateFloatAsState(if (state.canScrollForward) 1f else 0f, tween(180), label = "fadeForward")
    val ltr = LocalLayoutDirection.current == LayoutDirection.Ltr
    graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
        .drawWithContent {
            drawContent()
            val fade = length.toPx()
            val vertical = orientation == Orientation.Vertical
            val extent = if (vertical) size.height else size.width
            val start = if (vertical) insets.calculateTopPadding().toPx()
            else (if (ltr) insets.calculateLeftPadding(LayoutDirection.Ltr) else insets.calculateRightPadding(LayoutDirection.Ltr)).toPx()
            val end = extent - if (vertical) insets.calculateBottomPadding().toPx()
            else (if (ltr) insets.calculateRightPadding(LayoutDirection.Ltr) else insets.calculateLeftPadding(LayoutDirection.Ltr)).toPx()

            // Along the scroll axis: band [from, to) with alpha going a -> b.
            fun band(from: Float, to: Float, a: Float, b: Float) {
                if (to <= from) return
                val (x0, x1) = if (!vertical && !ltr) extent - to to extent - from else from to to
                val (ca, cb) = if (!vertical && !ltr) b to a else a to b
                val brush = if (vertical) Brush.verticalGradient(listOf(Color.Black.copy(alpha = ca), Color.Black.copy(alpha = cb)), startY = x0, endY = x1)
                else Brush.horizontalGradient(listOf(Color.Black.copy(alpha = ca), Color.Black.copy(alpha = cb)), startX = x0, endX = x1)
                drawRect(
                    brush,
                    topLeft = if (vertical) Offset(0f, x0) else Offset(x0, 0f),
                    size = if (vertical) Size(size.width, x1 - x0) else Size(x1 - x0, size.height),
                    blendMode = BlendMode.DstIn,
                )
            }

            if (back > 0f) {
                band(0f, start, 1f - back, 1f - back)
                band(start, start + fade, 1f - back, 1f)
            }
            if (forward > 0f) {
                band(end - fade, end, 1f, 1f - forward)
                band(end, extent, 1f - forward, 1f - forward)
            }
        }
}

/**
 * A bottom sheet's body: at most [maxFraction] of the screen tall, scrolling with faded
 * edges when it doesn't fit (landscape, large text). Scrolling only switches on when the
 * content overflows, so a drag on a sheet that fits still pulls the sheet down.
 * Goes after the sheet's background, so only the content fades.
 */
@Composable
fun Modifier.sheetScroll(maxFraction: Float = 0.9f): Modifier {
    val state = rememberScrollState()
    return heightIn(max = LocalConfiguration.current.screenHeightDp.dp * maxFraction)
        .edgeFade(state, Orientation.Vertical, length = 22.dp)
        .verticalScroll(state, enabled = state.maxValue in 1 until Int.MAX_VALUE)
}
