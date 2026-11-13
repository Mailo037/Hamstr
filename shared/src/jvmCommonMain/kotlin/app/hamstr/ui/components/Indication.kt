package app.hamstr.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.tween
import androidx.compose.foundation.IndicationNodeFactory
import androidx.compose.foundation.interaction.FocusInteraction
import androidx.compose.foundation.interaction.HoverInteraction
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.node.CompositionLocalConsumerModifierNode
import androidx.compose.ui.node.DelegatableNode
import androidx.compose.ui.node.DrawModifierNode
import androidx.compose.ui.node.currentValueOf
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.unit.dp
import app.hamstr.ui.theme.LocalPalette
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * How anything clickable answers, instead of Foundation's grey: the pointer resting on it lifts
 * it a little (ink at 5 %), a press a little more (ink at 9 %), and keyboard focus draws a 2 dp
 * accent ring just inside its edge. The ring only shows while the keyboard moves the focus, not
 * after a click, so a phone without a keyboard never sees it. HamstrTheme provides it as
 * LocalIndication, so every `clickable` gets it.
 *
 * Drawn inside the clip of what it marks; where there is none, with soft corners of its own.
 */
object HamstrIndication : IndicationNodeFactory {
    override fun create(interactionSource: InteractionSource): DelegatableNode = HamstrIndicationNode(interactionSource, hover = true, press = true)
    override fun equals(other: Any?): Boolean = other === this
    override fun hashCode(): Int = 0x4A11
}

/** Hover and keyboard focus without the press, for [pressable]: what it presses settles and scales instead. */
internal object HoverIndication : IndicationNodeFactory {
    override fun create(interactionSource: InteractionSource): DelegatableNode = HamstrIndicationNode(interactionSource, hover = true, press = false)
    override fun equals(other: Any?): Boolean = other === this
    override fun hashCode(): Int = 0x4A12
}

/** Only the keyboard's focus ring, for things that show the pointer on their own (menu items, the side bar's tabs). */
object FocusIndication : IndicationNodeFactory {
    override fun create(interactionSource: InteractionSource): DelegatableNode = HamstrIndicationNode(interactionSource, hover = false, press = false)
    override fun equals(other: Any?): Boolean = other === this
    override fun hashCode(): Int = 0x4A13
}

private const val HOVER_ALPHA = 0.05f
private const val PRESS_ALPHA = 0.09f
private const val RING_ALPHA = 0.65f

// In quicker than out: the answer starts with the input and lingers a moment after it.
private const val IN_MS = 120
private const val OUT_MS = 180

private class HamstrIndicationNode(
    private val source: InteractionSource,
    private val hover: Boolean,
    private val press: Boolean,
) : Modifier.Node(), DrawModifierNode, CompositionLocalConsumerModifierNode {
    private val hovered = Animatable(0f)
    private val pressed = Animatable(0f)
    private val focused = Animatable(0f)
    private var pressJob: Job? = null

    override fun onAttach() {
        coroutineScope.launch {
            val hovers = ArrayList<HoverInteraction.Enter>()
            val presses = ArrayList<PressInteraction.Press>()
            val focuses = ArrayList<FocusInteraction.Focus>()
            source.interactions.collect { i ->
                when (i) {
                    is HoverInteraction.Enter -> hovers += i
                    is HoverInteraction.Exit -> hovers -= i.enter
                    is PressInteraction.Press -> presses += i
                    is PressInteraction.Release -> presses -= i.press
                    is PressInteraction.Cancel -> presses -= i.press
                    is FocusInteraction.Focus -> focuses += i
                    is FocusInteraction.Unfocus -> focuses -= i.focus
                }
                if (hover) fade(hovered, hovers.isNotEmpty())
                if (press) pressTo(presses.isNotEmpty())
                fade(focused, focuses.isNotEmpty())
            }
        }
    }

    private fun fade(a: Animatable<Float, AnimationVector1D>, on: Boolean) {
        val target = if (on) 1f else 0f
        if (a.targetValue == target) return
        coroutineScope.launch { to(a, target) }
    }

    // A quick click still shows: the press fades all the way in before it fades out.
    private fun pressTo(on: Boolean) {
        val target = if (on) 1f else 0f
        if (pressed.targetValue == target) return
        val before = pressJob
        if (on) before?.cancel()
        pressJob = coroutineScope.launch {
            if (!on) before?.join()
            to(pressed, target)
        }
    }

    private suspend fun to(a: Animatable<Float, AnimationVector1D>, target: Float) {
        if (currentValueOf(LocalReduceMotion)) a.snapTo(target)
        else a.animateTo(target, tween(if (target > 0f) IN_MS else OUT_MS))
    }

    override fun ContentDrawScope.draw() {
        drawContent()
        val h = hovered.value
        val p = pressed.value
        val f = focused.value
        if (h == 0f && p == 0f && f == 0f) return
        val palette = currentValueOf(LocalPalette)
        val corner = minOf(CORNER.toPx(), size.minDimension / 2f)
        val fill = maxOf(h * HOVER_ALPHA, p * PRESS_ALPHA)
        if (fill > 0f) drawRoundRect(palette.ink.copy(alpha = fill), cornerRadius = CornerRadius(corner))
        // A click moves the input mode to touch, Tab and the arrows back to keyboard.
        if (f > 0f && currentValueOf(LocalInputModeManager).inputMode == InputMode.Keyboard) {
            val width = RING.toPx()
            val inset = INSET.toPx() + width / 2f
            if (size.width <= inset * 2f || size.height <= inset * 2f) return
            drawRoundRect(
                palette.accent.copy(alpha = RING_ALPHA * f),
                topLeft = Offset(inset, inset),
                size = Size(size.width - inset * 2f, size.height - inset * 2f),
                cornerRadius = CornerRadius((corner - inset).coerceAtLeast(0f)),
                style = Stroke(width),
            )
        }
    }
}

// Just inside a row's 16 dp corners and rounder than smaller clips, so a clip never cuts the ring; the ring keeps concentric with it.
private val CORNER = 15.dp
private val RING = 2.dp
private val INSET = 1.dp
