package app.hamstr.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.Dp
import app.hamstr.ui.theme.Hamstr
import app.hamstr.ui.theme.LocalFx
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.max
import kotlin.random.Random

/** The Hamstr mark: a hamster head made of characters (same grid as tools/render_icon.py). */
val MARK = listOf(
    "...###....###...",
    "..#####..#####..",
    "...##########...",
    "..############..",
    ".##############.",
    ".##############.",
    "####0######0####",
    "################",
    "#***########***#",
    ".***########***.",
    "..############..",
    "....########....",
)

/**
 * Everything the mark can do: blink, wink, munch (cheeks puff and chew), hop, perk its ears.
 * [pull] fills the cheeks from outside (pull-to-stash), [intro] assembles it from noise.
 */
@Stable
class MarkState(intro: Float = 1f) {
    val leftEye = Animatable(1f)
    val rightEye = Animatable(1f)
    val cheeks = Animatable(0f)
    val hop = Animatable(0f)
    val squash = Animatable(0f)
    val ears = Animatable(0f)
    val intro = Animatable(intro)
    var pull by mutableFloatStateOf(0f)

    suspend fun blink() = coroutineScope {
        launch { leftEye.animateTo(0f, tween(70)); leftEye.animateTo(1f, tween(120)) }
        launch { rightEye.animateTo(0f, tween(70)); rightEye.animateTo(1f, tween(120)) }
    }

    suspend fun wink() = coroutineScope {
        launch { rightEye.animateTo(0f, tween(90)); delay(220); rightEye.animateTo(1f, tween(160)) }
        launch { leftEye.animateTo(0.75f, tween(90)); delay(220); leftEye.animateTo(1f, tween(160)) }
        launch { cheeks.animateTo(0.45f, tween(120)); delay(160); cheeks.animateTo(0f, spring(0.4f, Spring.StiffnessLow)) }
        launch { ears.animateTo(1f, tween(110)); ears.animateTo(0f, spring(0.3f, Spring.StiffnessMediumLow)) }
    }

    /** Cheeks fill up and chew: plays when something finishes downloading. */
    suspend fun munch() = coroutineScope {
        launch { leftEye.animateTo(0.35f, tween(120)); delay(520); leftEye.animateTo(1f, tween(160)) }
        launch { rightEye.animateTo(0.35f, tween(120)); delay(520); rightEye.animateTo(1f, tween(160)) }
        launch {
            cheeks.animateTo(1f, spring(0.45f, Spring.StiffnessMedium))
            repeat(3) {
                cheeks.animateTo(0.72f, tween(90))
                cheeks.animateTo(1f, tween(110))
            }
            cheeks.animateTo(0f, spring(0.35f, Spring.StiffnessLow))
        }
    }

    suspend fun hop() = coroutineScope {
        launch { squash.animateTo(0.35f, tween(80)) }.join()
        launch { squash.animateTo(-0.2f, tween(140)) }
        launch { ears.animateTo(1f, tween(120)); ears.animateTo(0f, spring(0.3f, Spring.StiffnessLow)) }
        hop.animateTo(1f, tween(190, easing = LinearOutSlowInEasing))
        hop.animateTo(0f, tween(170, easing = FastOutLinearInEasing))
        squash.snapTo(0.3f)
        squash.animateTo(0f, spring(0.3f, Spring.StiffnessMedium))
    }

    /** Tapping it: a wink, sometimes a happy hop. */
    suspend fun poke() {
        if (Random.nextFloat() < 0.4f) coroutineScope { launch { hop() }; launch { delay(120); wink() } } else wink()
    }

    /** Builds the mark out of ASCII noise, then says hi. */
    suspend fun assemble(durationMs: Int = 650) {
        intro.snapTo(0f)
        intro.animateTo(1f, tween(durationMs, easing = LinearOutSlowInEasing))
        coroutineScope { launch { hop() }; launch { delay(160); wink() } }
    }
}

/** A mark state that blinks on its own every few seconds (unless motion is reduced). */
@Composable
fun rememberMarkState(autoBlink: Boolean = true): MarkState {
    val state = remember { MarkState() }
    val moods = LocalFx.current.moods
    if (autoBlink && moods && LocalAmbient.current) LaunchedEffect(state) {
        while (true) {
            delay(Random.nextLong(2600, 6200))
            when {
                Random.nextFloat() < 0.12f -> state.wink()
                Random.nextFloat() < 0.25f -> { state.blink(); delay(140); state.blink() }
                else -> state.blink()
            }
        }
    }
    return state
}

@Composable
fun HamstrMark(
    size: Dp,
    modifier: Modifier = Modifier,
    state: MarkState = rememberMarkState(),
    background: Boolean = true,
    interactive: Boolean = true,
) {
    val c = Hamstr.colors
    val paint = rememberMonoPaint()
    val scope = rememberCoroutineScope()
    val haptics = LocalHapticFeedback.current
    val cheekHot = Color(0xFFFFC27A)
    val tap = if (interactive) Modifier.clickable(remember { MutableInteractionSource() }, null) {
        haptics.performHapticFeedback(HapticFeedbackType.ContextClick)
        scope.launch { state.poke() }
    } else Modifier

    Canvas(
        modifier
            .size(size)
            .then(tap)
            .graphicsLayer {
                val sq = state.squash.value
                translationY = -state.hop.value * this.size.height * 0.16f
                scaleY = 1f - sq * 0.22f
                scaleX = 1f + sq * 0.14f
                transformOrigin = TransformOrigin(0.5f, 1f)
            },
    ) {
        if (background) drawRoundRect(c.black, cornerRadius = CornerRadius(this.size.width * 0.28f))
        val cols = MARK[0].length
        val cell = this.size.width * 0.78f / cols
        val ox = (this.size.width - cell * cols) / 2f
        val oy = (this.size.height - cell * MARK.size * 1.1f) / 2f
        val base = cell * 1.15f
        paint.textSize = base
        val baseline = paint.centerBaseline
        val canvas = drawContext.canvas
        val intro = state.intro.value
        val puff = max(state.cheeks.value, state.pull).coerceIn(0f, 1.3f)
        val ears = state.ears.value
        var k = 0
        MARK.forEachIndexed { r, line ->
            line.forEachIndexed { col, ch ->
                if (ch == '.') return@forEachIndexed
                val index = k++
                var x = ox + col * cell + cell / 2f
                var y = oy + r * cell * 1.1f + cell * 0.55f
                // Assembly: each character arrives at its own moment, as noise first.
                val arrive = hash(index * 1.7f) * 0.75f
                if (intro < arrive) return@forEachIndexed
                val settling = intro < arrive + 0.22f
                var glyph = ch
                var color = if (ch == '*') c.accent else c.ink
                var size = base
                var scaleY = 1f
                if (settling) {
                    glyph = NOISE[(hash(index + intro * 9f) * NOISE.length).toInt().coerceIn(0, NOISE.length - 1)]
                    color = c.muted.copy(alpha = ((intro - arrive) / 0.22f).coerceIn(0.15f, 1f))
                } else when {
                    r <= 1 -> y -= ears * cell * 0.35f
                    ch == '0' -> scaleY = max(if (col < cols / 2) state.leftEye.value else state.rightEye.value, 0.08f)
                    ch == '*' -> {
                        size = base * (1f + puff * 0.55f)
                        x += (if (col < cols / 2) -1 else 1) * puff * cell * 0.38f
                        y += puff * cell * 0.12f
                        color = lerp(c.accent, cheekHot, (puff * 0.8f).coerceAtMost(1f))
                    }
                }
                paint.textSize = size
                paint.color = color.toArgb()
                if (scaleY != 1f) {
                    scale(1f, scaleY, Offset(x, y)) { paint.draw(canvas, glyph.toString(), x, y + baseline) }
                } else {
                    paint.draw(canvas, glyph.toString(), x, y + baseline)
                }
            }
        }
        paint.textSize = base
    }
}
