package app.hamstr.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
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
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.animation.core.withInfiniteAnimationFrameMillis
import kotlinx.coroutines.delay
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.IntOffset
import app.hamstr.ui.theme.TravelEasing
import app.hamstr.ui.theme.LocalFx
import kotlinx.coroutines.flow.first
import kotlin.math.abs
import kotlin.math.sign
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith

/** True when the system animator scale is 0: decorative motion is skipped, travel snaps. */
val LocalReduceMotion = staticCompositionLocalOf { false }

/**
 * False while nobody looks: on the desktop, while the window isn't in front (another app has
 * the focus, it's minimized or in the tray). Ambient motion (the raster's drift, the hamster's
 * breath and blinks, loading rain, pulses) stands still then, so Hamstr draws nothing.
 */
val LocalAmbient = compositionLocalOf { true }

/**
 * A clock for ambient motion that ticks [fps] times a second instead of every display frame:
 * on a 144 or 240 Hz monitor a breath would otherwise redraw the whole window that often.
 * It stops while [LocalAmbient] is false or motion is reduced.
 */
@Composable
fun rememberAmbientClock(fps: Int, enabled: Boolean = true): State<Long> {
    val time = remember { mutableLongStateOf(0L) }
    if (enabled && LocalAmbient.current && !LocalReduceMotion.current) LaunchedEffect(fps) {
        val step = 1000L / fps
        while (true) {
            withInfiniteAnimationFrameMillis { time.longValue = it }
            delay(step)
        }
    }
    return time
}

/**
 * The motion that recurs across the app (DESIGN.md, Motion). Entries spring, exits are
 * short tweens; a spec that is used once stays inline, with a comment saying why.
 */
object Specs {
    /** Press feedback: quick, with a little give. */
    fun <T> press() = spring<T>(dampingRatio = 0.55f, stiffness = 900f)

    /** A state icon scaling in (play/pause, a download's button). */
    fun <T> swap() = spring<T>(dampingRatio = 0.5f, stiffness = 600f)

    /** Something arriving with an overshoot: the done check, an armed swipe action. */
    fun <T> pop() = spring<T>(dampingRatio = 0.4f, stiffness = 500f)

    /** Back to rest after a drag lets go: a short, visible wobble. */
    fun <T> settle() = spring<T>(dampingRatio = 0.5f, stiffness = 480f)

    /** Values that follow data, such as progress, instead of jumping between updates. */
    fun <T> glide() = spring<T>(stiffness = Spring.StiffnessLow)

    /** A selection pill arriving at its option, overshooting a little. */
    fun <T> arrive() = spring<T>(dampingRatio = 0.62f, stiffness = 520f)

    /** A page sliding in, or the pager moving to a tab. */
    fun <T> enter() = spring<T>(dampingRatio = 0.86f, stiffness = 420f)

    /** Rows moving to their new place in a list. */
    val place = spring(dampingRatio = 0.8f, stiffness = Spring.StiffnessMediumLow, visibilityThreshold = IntOffset(1, 1))

    /** Rows of a list fading in and out; arriving takes longer than leaving. */
    fun <T> listIn() = tween<T>(320)

    fun <T> listOut() = tween<T>(160)

    /** Colours and sizes easing over (selection, a sheet changing height, a chevron turning). */
    fun <T> blend() = tween<T>(220)

    /** Content fading in. */
    fun <T> appear() = tween<T>(160)

    /** Content fading out: leaving is quicker than arriving. */
    fun <T> disappear() = tween<T>(120)

    /** Something travelling off screen: a dismissed sheet or page, a row flying out. */
    fun <T> leave(durationMs: Int = 200) = tween<T>(durationMs, easing = TravelEasing)
}

/** Slate's scale crossfade for a state icon that changes (play/pause, a download's button). */
fun iconSwap(): ContentTransform =
    (scaleIn(Specs.swap(), initialScale = 0.4f) + fadeIn(Specs.appear())) togetherWith
        (scaleOut(Specs.disappear(), targetScale = 0.4f) + fadeOut(Specs.disappear()))

/**
 * 0 when off, 1 when picked, eased in between, so a pill's colours blend into the
 * accent instead of jumping. Lerp the colours with it.
 */
@Composable
fun animateSelection(on: Boolean): Float {
    val reduce = LocalReduceMotion.current
    val p by animateFloatAsState(if (on) 1f else 0f, if (reduce) snap() else Specs.blend(), label = "selection")
    return p
}

/** The system asks for less motion: Android's animator scale at 0. */
@Composable
expect fun systemReducesMotion(): Boolean

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
