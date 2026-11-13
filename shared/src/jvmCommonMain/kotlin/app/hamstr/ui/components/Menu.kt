package app.hamstr.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import app.hamstr.res.*
import app.hamstr.ui.theme.Hamstr
import org.jetbrains.compose.resources.DrawableResource

/**
 * A menu that drops out of where it was opened, for a pointer: a right-click's menu at the
 * pointer, a button's under the button. It fades and grows in from a little smaller, stays
 * inside the window (opening the other way where there is no room) and closes on a click
 * beside it, on Esc, or when an item closes it.
 *
 * [at] is a point inside the composable that holds the menu, such as where a right-click
 * landed; without it the menu hangs under that composable, lined up with its start (with its
 * [end] for a button at the end of a row, or where the window ends first).
 */
@Composable
fun DropMenu(open: Boolean, onDismiss: () -> Unit, at: IntOffset? = null, end: Boolean = false, content: @Composable ColumnScope.() -> Unit) {
    val c = Hamstr.colors
    val shown = remember { MutableTransitionState(false) }
    shown.targetState = open
    if (!shown.currentState && !shown.targetState) return
    val density = LocalDensity.current
    val position = remember(at, end, density) { MenuPosition(at, end, with(density) { 6.dp.roundToPx() }, with(density) { 8.dp.roundToPx() }) }
    Popup(popupPositionProvider = position, onDismissRequest = onDismiss, properties = PopupProperties(focusable = true)) {
        AnimatedVisibility(
            shown,
            enter = fadeIn(Specs.appear()) + scaleIn(spring(dampingRatio = 0.8f, stiffness = 700f), initialScale = 0.92f, transformOrigin = TransformOrigin(0.5f, 0f)),
            exit = fadeOut(Specs.disappear()) + scaleOut(Specs.disappear(), targetScale = 0.96f, transformOrigin = TransformOrigin(0.5f, 0f)),
        ) {
            // Panel 14, padding 4, items 10: concentric.
            Column(
                Modifier
                    .width(IntrinsicSize.Max)
                    .widthIn(min = 200.dp, max = 300.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(c.raised)
                    .padding(4.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
                content = content,
            )
        }
    }
}

/**
 * One line of a [DropMenu]: an icon, the label, and a check when it is the picked one. The
 * pointer resting on it lifts it a step, the keyboard's focus rings it; [danger] is for what
 * can't be undone.
 */
@Composable
fun MenuItem(
    label: String,
    onClick: () -> Unit,
    icon: DrawableResource? = null,
    checked: Boolean = false,
    danger: Boolean = false,
) {
    val c = Hamstr.colors
    val source = remember { MutableInteractionSource() }
    val hovered by source.collectIsHoveredAsState()
    val lift = animateSelection(hovered)
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 36.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(c.hover.copy(alpha = lift))
            .hoverable(source)
            // The lift above shows the pointer; the keyboard's focus gets the ring.
            .clickable(source, FocusIndication, onClick = onClick)
            .padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Icon(icon, tint = if (danger) c.red else if (checked) c.accent else c.muted, size = 18.dp)
            Spacer(Modifier.width(10.dp))
        }
        Txt(label, style = Hamstr.type.body, color = if (danger) c.red else c.ink, maxLines = 1, modifier = Modifier.weight(1f))
        Box(Modifier.padding(start = 12.dp).width(16.dp), contentAlignment = Alignment.Center) {
            if (checked) Icon(Res.drawable.ic_check, tint = c.accent, size = 16.dp)
        }
    }
}

/** A small label over a group of items in a [DropMenu]. */
@Composable
fun MenuLabel(text: String) =
    Txt(text, style = Hamstr.type.badge, color = Hamstr.colors.ghost, maxLines = 1, modifier = Modifier.padding(start = 10.dp, end = 10.dp, top = 8.dp, bottom = 2.dp))

/** A line between groups of items in a [DropMenu]. */
@Composable
fun MenuDivider() =
    Box(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 3.dp).height(1.dp).background(Hamstr.colors.line))

private class MenuPosition(private val at: IntOffset?, private val end: Boolean, private val gap: Int, private val margin: Int) : PopupPositionProvider {
    override fun calculatePosition(anchorBounds: IntRect, windowSize: IntSize, layoutDirection: LayoutDirection, popupContentSize: IntSize): IntOffset {
        val w = popupContentSize.width
        val h = popupContentSize.height
        var x: Int
        var y: Int
        if (at != null) {
            x = anchorBounds.left + at.x
            y = anchorBounds.top + at.y
            if (x + w > windowSize.width - margin) x -= w
            if (y + h > windowSize.height - margin) y -= h
        } else {
            x = if (end) anchorBounds.right - w else anchorBounds.left
            y = anchorBounds.bottom + gap
            if (x + w > windowSize.width - margin) x = anchorBounds.right - w
            if (y + h > windowSize.height - margin) y = anchorBounds.top - gap - h
        }
        return IntOffset(
            x.coerceIn(margin, maxOf(margin, windowSize.width - margin - w)),
            y.coerceIn(margin, maxOf(margin, windowSize.height - margin - h)),
        )
    }
}
