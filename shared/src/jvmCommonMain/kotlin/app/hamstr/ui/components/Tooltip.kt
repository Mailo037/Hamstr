package app.hamstr.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import app.hamstr.Platform
import app.hamstr.ui.theme.Hamstr
import kotlinx.coroutines.delay

/** Whether the pointer rests on the thing a [Tip] belongs to. */
@Stable
class TipState {
    internal var hovered by mutableStateOf(false)
    internal var pressed = false
}

@Composable
fun rememberTip(): TipState = remember { TipState() }

/**
 * Follows the pointer for [tip] on the desktop: resting on the thing shows its [Tip], leaving
 * or pressing it hides it until the pointer comes back. A phone has no pointer to rest, so
 * there it does nothing.
 */
fun Modifier.hoverTip(tip: TipState): Modifier = if (!Platform.desktop) this else pointerInput(tip) {
    awaitPointerEventScope {
        while (true) {
            val e = awaitPointerEvent(PointerEventPass.Initial)
            when (e.type) {
                PointerEventType.Enter, PointerEventType.Move -> if (!tip.pressed) tip.hovered = true
                PointerEventType.Press -> { tip.pressed = true; tip.hovered = false }
                PointerEventType.Exit -> { tip.pressed = false; tip.hovered = false }
            }
        }
    }
}

/** The tooltip for [tip]; call it inside the thing it explains (see [hoverTip]). */
@Composable
fun Tip(tip: TipState, text: String) = Tip(tip.hovered, text)

/**
 * Once a tip has shown, its neighbours' tips come quickly for a moment, as with the system's own:
 * sliding along a row of buttons reads them one after another without waiting at each.
 */
private object TipWarmth {
    var open = 0
    var until = 0L
}

/**
 * Hamstr's own tooltip, not the system's: after the pointer rested on its thing for half a
 * second (a tenth right after another tip), [text] fades in on a raised pill under it (over it
 * where the window ends). It sits in the composable it explains and only shows on the desktop.
 */
@Composable
fun Tip(visible: Boolean, text: String) {
    if (!Platform.desktop || text.isEmpty()) return
    var due by remember { mutableStateOf(false) }
    LaunchedEffect(visible) {
        if (visible) delay(if (TipWarmth.open > 0 || System.currentTimeMillis() < TipWarmth.until) 100 else 500)
        due = visible
    }
    DisposableEffect(due) {
        if (due) TipWarmth.open++
        onDispose {
            if (due) {
                TipWarmth.open--
                TipWarmth.until = System.currentTimeMillis() + 800
            }
        }
    }
    val shown = remember { MutableTransitionState(false) }
    shown.targetState = due
    if (!shown.currentState && !shown.targetState) return
    val c = Hamstr.colors
    val density = LocalDensity.current
    val position = remember(density) { TipPosition(with(density) { 6.dp.roundToPx() }) }
    Popup(popupPositionProvider = position, properties = PopupProperties(focusable = false)) {
        AnimatedVisibility(
            shown,
            enter = fadeIn(Specs.appear()) + scaleIn(spring(dampingRatio = 0.7f, stiffness = 700f), initialScale = 0.9f),
            exit = fadeOut(Specs.disappear()),
        ) {
            Txt(
                text,
                style = Hamstr.type.small,
                color = c.ink,
                maxLines = 2,
                modifier = Modifier
                    .widthIn(max = 260.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(c.raised)
                    .padding(horizontal = 9.dp, vertical = 5.dp),
            )
        }
    }
}

/** Centred under its thing, or over it when the window ends below; never past the window's sides. */
private class TipPosition(private val gap: Int) : PopupPositionProvider {
    override fun calculatePosition(anchorBounds: IntRect, windowSize: IntSize, layoutDirection: LayoutDirection, popupContentSize: IntSize): IntOffset {
        val x = anchorBounds.center.x - popupContentSize.width / 2
        val below = anchorBounds.bottom + gap
        val y = if (below + popupContentSize.height > windowSize.height - gap) anchorBounds.top - gap - popupContentSize.height else below
        return IntOffset(x.coerceIn(gap, maxOf(gap, windowSize.width - gap - popupContentSize.width)), y.coerceAtLeast(gap))
    }
}
