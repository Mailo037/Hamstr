package app.hamstr.ui.components

import android.provider.Settings
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import app.hamstr.ui.theme.LocalFx
import kotlinx.coroutines.flow.first
import kotlin.math.abs
import kotlin.math.sign

/** True when the system animator scale is 0: decorative motion is skipped, travel snaps. */
val LocalReduceMotion = staticCompositionLocalOf { false }

/**
 * 0 when off, 1 when picked, eased in between, so a pill's colours blend into the
 * accent instead of jumping. Lerp the colours with it.
 */
@Composable
fun animateSelection(on: Boolean): Float {
    val reduce = LocalReduceMotion.current
    val p by animateFloatAsState(if (on) 1f else 0f, if (reduce) snap() else tween(220), label = "selection")
    return p
}

@Composable
fun systemReducesMotion(): Boolean {
    val context = LocalContext.current
    return remember {
        Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
    }
}

/**
 * Follows a moving value and turns its speed into a signed stretch (-max..max), the way
 * Slate's switch thumb and tab pill deform in the direction of travel. Only runs frames
 * while the value is moving or the stretch is settling back.
 */
@Composable
fun rememberVelocityStretch(
    value: () -> Float,
    sensitivity: Float = 0.00012f,
    max: Float = 0.32f,
): State<Float> {
    val stretch = remember { mutableFloatStateOf(0f) }
    val read = rememberUpdatedState(value)
    val reduce = LocalReduceMotion.current || !LocalFx.current.stretch
    if (!reduce) LaunchedEffect(Unit) {
        var last = read.value()
        while (true) {
            snapshotFlow { read.value() }.first { it != last }
            var lastT = 0L
            var idle = 0
            while (idle < 4) {
                withFrameNanos { t ->
                    val v = read.value()
                    val dt = if (lastT == 0L) 1 / 60f else ((t - lastT) / 1e9f).coerceAtLeast(1 / 240f)
                    val vel = (v - last) / dt
                    last = v
                    lastT = t
                    val target = (abs(vel) * sensitivity).coerceAtMost(max) * sign(vel)
                    // Ease towards the target: quick to stretch, a touch slower to recover.
                    val k = if (abs(target) > abs(stretch.floatValue)) 0.35f else 0.18f
                    stretch.floatValue += (target - stretch.floatValue) * k
                    if (abs(stretch.floatValue) < 0.002f && vel == 0f) idle++ else idle = 0
                }
            }
            stretch.floatValue = 0f
        }
    }
    return stretch
}

/**
 * Applies a horizontal stretch: longer in the direction of travel, a little thinner,
 * anchored at the trailing side so the leading edge reaches ahead.
 */
fun Modifier.stretchX(stretch: () -> Float): Modifier = graphicsLayer {
    val s = stretch()
    scaleX = 1f + abs(s)
    scaleY = 1f - abs(s) * 0.35f
    transformOrigin = TransformOrigin(if (s >= 0f) 0f else 1f, 0.5f)
}

fun Modifier.stretchY(stretch: () -> Float): Modifier = graphicsLayer {
    val s = stretch()
    scaleY = 1f + abs(s)
    scaleX = 1f - abs(s) * 0.35f
    transformOrigin = TransformOrigin(0.5f, if (s >= 0f) 0f else 1f)
}

/** iOS-style rubber band: past [limit] the value keeps moving, but ever slower. */
fun rubberBand(offset: Float, limit: Float, resistance: Float = 0.55f): Float {
    if (abs(offset) <= limit) return offset
    val over = abs(offset) - limit
    val damped = limit * (1f - 1f / (over * resistance / limit + 1f))
    return sign(offset) * (limit + damped)
}
