package app.hamstr.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.IntOffset
import kotlinx.coroutines.launch
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.indication
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import org.jetbrains.compose.resources.DrawableResource
import org.jetbrains.compose.resources.painterResource
import androidx.compose.foundation.progressSemantics
import androidx.compose.foundation.selection.toggleable
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.text
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.hamstr.ui.theme.Hamstr
import app.hamstr.ui.theme.Radii
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable

@Composable
fun Txt(
    text: String,
    style: TextStyle = Hamstr.type.body,
    color: Color = Hamstr.colors.ink,
    modifier: Modifier = Modifier,
    maxLines: Int = Int.MAX_VALUE,
) {
    BasicText(
        text = text,
        style = style.copy(color = color),
        modifier = modifier,
        maxLines = maxLines,
        overflow = TextOverflow.Ellipsis,
    )
}

/**
 * `[ LABEL ]` in mono, Glyph's ASCII badge: a quiet fact in `ghost`, or live state in accent. A
 * screen reader says the label, not the brackets.
 */
@Composable
fun Badge(label: String, accent: Boolean = false, modifier: Modifier = Modifier) {
    Txt(
        "[ ${label.uppercase()} ]",
        style = Hamstr.type.tag,
        color = if (accent) Hamstr.colors.accent else Hamstr.colors.ghost,
        modifier = modifier.clearAndSetSemantics { text = AnnotatedString(label) },
        maxLines = 1,
    )
}

@Composable
fun Icon(res: DrawableResource, tint: Color = Hamstr.colors.ink, size: Dp = 20.dp, modifier: Modifier = Modifier) {
    Image(
        painter = painterResource(res),
        contentDescription = null,
        colorFilter = ColorFilter.tint(tint),
        modifier = modifier.size(size),
    )
}

/**
 * Press feedback from Slate: settle by translateY(1px) scale(.97). For things that stand on
 * their own (buttons, tiles, chips, cards); rows inside a panel use `clickable`, which dims.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun Modifier.pressable(
    onClick: () -> Unit,
    label: String? = null,
    enabled: Boolean = true,
    pressedScale: Float = 0.97f,
    onLongClick: (() -> Unit)? = null,
    // A part that shows the hover and the keyboard focus itself (IconButton's visible button)
    // shares the source and passes hover = false; otherwise they show over all of this.
    interactionSource: MutableInteractionSource? = null,
    hover: Boolean = true,
): Modifier {
    val source = interactionSource ?: remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) pressedScale else 1f, Specs.press(), label = "press")
    val dy by animateDpAsState(if (pressed) 1.dp else 0.dp, tween(90), label = "pressY")
    return this
        .then(if (label != null) Modifier.semantics { contentDescription = label } else Modifier)
        .offset(y = dy)
        .scale(scale)
        .onRightClick(onLongClick.takeIf { enabled })
        .combinedClickable(interactionSource = source, indication = if (hover) HoverIndication else null, enabled = enabled, role = Role.Button, onLongClick = onLongClick, onClick = onClick)
}

/**
 * A right-click does what a long press does: on the desktop (and with a mouse on a phone) the
 * menu a long press opens is one click away. Taken before the click handlers see the press.
 */
@Composable
fun Modifier.onRightClick(action: (() -> Unit)?): Modifier {
    if (action == null) return this
    val current by rememberUpdatedState(action)
    return pointerInput(Unit) {
        awaitPointerEventScope {
            while (true) {
                val e = awaitPointerEvent(PointerEventPass.Initial)
                if (e.type == PointerEventType.Press && e.buttons.isSecondaryPressed) {
                    e.changes.forEach { it.consume() }
                    current()
                }
            }
        }
    }
}

enum class ButtonKind { Primary, Neutral, Ghost }

@Composable
fun HButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    kind: ButtonKind = ButtonKind.Neutral,
    icon: DrawableResource? = null,
    enabled: Boolean = true,
) {
    val c = Hamstr.colors
    val bg = when (kind) {
        ButtonKind.Primary -> c.accent
        ButtonKind.Neutral -> c.surface
        ButtonKind.Ghost -> Color.Transparent
    }
    val fg = if (kind == ButtonKind.Primary) c.onAccent else c.ink
    Row(
        modifier
            .height(46.dp)
            .clip(RoundedCornerShape(Radii.control))
            .background(if (enabled) bg else c.surface)
            .pressable(onClick, enabled = enabled)
            .padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) Icon(icon, tint = if (enabled) fg else c.ghost, size = 18.dp)
        Txt(text, style = Hamstr.type.button, color = if (enabled) fg else c.ghost, maxLines = 1)
    }
}

@Composable
fun IconButton(
    icon: DrawableResource,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    tint: Color = Hamstr.colors.muted,
    background: Color = Hamstr.colors.surface,
    size: Dp = 38.dp,
) {
    // The visible button can be small; the finger always gets at least 48 dp. A pointer
    // resting on it gets the label as a tooltip.
    val tip = rememberTip()
    val source = remember { MutableInteractionSource() }
    Box(
        modifier
            .sizeIn(minWidth = 48.dp, minHeight = 48.dp)
            .hoverTip(tip)
            .pressable(onClick, label = label, pressedScale = 0.92f, interactionSource = source, hover = false),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .size(size)
                .clip(RoundedCornerShape(12.dp))
                .background(background)
                // Hover and focus on the button you see, not on the larger area the finger gets.
                .indication(source, HoverIndication),
            contentAlignment = Alignment.Center,
        ) { Icon(icon, tint = tint, size = 20.dp) }
        Tip(tip, label)
    }
}

/**
 * A square neutral button with only an icon, as tall as an [HButton] beside it: the lesser
 * action in a row of buttons (share, more options). The label is for screen readers and the
 * tooltip.
 */
@Composable
fun SquareButton(icon: DrawableResource, label: String, onClick: () -> Unit, modifier: Modifier = Modifier, tint: Color = Hamstr.colors.ink) {
    val tip = rememberTip()
    Box(
        modifier
            .size(46.dp)
            .clip(RoundedCornerShape(Radii.control))
            .background(Hamstr.colors.surface)
            .hoverTip(tip)
            .pressable(onClick, label = label),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, tint = tint, size = 20.dp)
        Tip(tip, label)
    }
}

/**
 * A label on a picture (quality, length, state): mono on a dark pill, readable over any
 * frame. [badge] puts it in brackets like [Badge].
 */
@Composable
fun PictureLabel(text: String, modifier: Modifier = Modifier, accent: Boolean = false, badge: Boolean = false) {
    Txt(
        if (badge) "[ ${text.uppercase()} ]" else text,
        style = Hamstr.type.badge,
        color = if (accent) Hamstr.colors.accent else Color.White,
        maxLines = 1,
        modifier = modifier
            .clip(RoundedCornerShape(6.dp))
            .background(Color.Black.copy(alpha = 0.65f))
            .padding(horizontal = 6.dp, vertical = 2.dp),
    )
}

/** Flat 3dp progress line, accent fill; a screen reader hears how far it is. */
@Composable
fun ProgressLine(progress: Float, modifier: Modifier = Modifier, color: Color = Hamstr.colors.accent) {
    Box(
        modifier
            .progressSemantics(progress.coerceIn(0f, 1f))
            .fillMaxWidth()
            .height(3.dp)
            .clip(CircleShape)
            .background(Hamstr.colors.line),
    ) {
        Box(
            Modifier
                .fillMaxWidth(progress.coerceIn(0f, 1f))
                .fillMaxHeight()
                .clip(CircleShape)
                .background(color),
        )
    }
}

/** A borderless rounded group of rows (Slate panel). */
@Composable
fun Group(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(Radii.panel))
            .background(Hamstr.colors.overlay)
            .padding(4.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
        content = content,
    )
}

@Composable
fun SectionHeader(title: String, trailing: String? = null, modifier: Modifier = Modifier) {
    Row(
        modifier
            .fillMaxWidth()
            .padding(start = 6.dp, end = 6.dp, top = 18.dp, bottom = 10.dp),
        verticalAlignment = Alignment.Bottom,
    ) {
        Txt(title, style = Hamstr.type.section, modifier = Modifier.weight(1f))
        if (trailing != null) Txt(trailing, style = Hamstr.type.mono, color = Hamstr.colors.ghost)
    }
}

/** Slate segmented control on [DragPillRow]: tap or drag the pill between options. */
@Composable
fun <T> Segmented(
    options: List<T>,
    selected: T,
    label: (T) -> String,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
    background: Color = Hamstr.colors.canvas,
) {
    val c = Hamstr.colors
    DragPillRow(
        count = options.size,
        selected = options.indexOf(selected).coerceAtLeast(0),
        onSelect = { onSelect(options[it]) },
        itemHeight = 36.dp,
        pillRadius = 10.dp,
        pillColor = c.hover,
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(Radii.control))
            .background(background)
            .padding(4.dp),
    ) { i, near ->
        Txt(label(options[i]), style = Hamstr.type.bodyMedium, color = lerp(c.muted, c.ink, near), maxLines = 1)
    }
}

/**
 * Switch with a smaller off thumb (Slate). Tap or drag the thumb; it stretches with its
 * speed, the track colour follows it continuously and it commits at the midpoint.
 */
@Composable
fun HSwitch(checked: Boolean, onCheckedChange: ((Boolean) -> Unit)? = null, modifier: Modifier = Modifier) {
    val c = Hamstr.colors
    val scope = rememberCoroutineScope()
    val haptics = LocalHapticFeedback.current
    val change by rememberUpdatedState(onCheckedChange)
    val pos = remember { Animatable(if (checked) 1f else 0f) }
    var dragging by remember { mutableStateOf(false) }
    val travel = with(LocalDensity.current) { 16.dp.toPx() }
    val stretch by rememberVelocityStretch({ pos.value * travel }, sensitivity = 0.00028f, max = 0.45f)
    LaunchedEffect(checked) { if (!dragging) pos.animateTo(if (checked) 1f else 0f, spring(dampingRatio = 0.55f, stiffness = 650f)) }

    fun commit(to: Boolean) {
        haptics.performHapticFeedback(if (to) HapticFeedbackType.ToggleOn else HapticFeedbackType.ToggleOff)
        change?.invoke(to)
    }

    val p = pos.value.coerceIn(0f, 1f)
    Box(
        modifier
            .width(42.dp)
            .height(26.dp)
            .clip(CircleShape)
            .background(lerp(c.hover, c.accent, p))
            .then(
                if (onCheckedChange == null) Modifier else Modifier
                    // On or off for a screen reader too, not just "switch".
                    .toggleable(checked, remember { MutableInteractionSource() }, null, role = Role.Switch) { commit(it) }
                    .pointerInput(Unit) {
                        detectHorizontalDragGestures(
                            onDragStart = { dragging = true },
                            onHorizontalDrag = { ch, dx ->
                                ch.consume()
                                scope.launch { pos.snapTo((pos.value + dx / travel).coerceIn(-0.25f, 1.25f)) }
                            },
                            onDragEnd = {
                                dragging = false
                                val to = pos.value > 0.5f
                                scope.launch { pos.animateTo(if (to) 1f else 0f, spring(dampingRatio = 0.55f, stiffness = 650f)) }
                                if (to != checked) commit(to)
                            },
                            onDragCancel = { dragging = false; scope.launch { pos.animateTo(if (checked) 1f else 0f) } },
                        )
                    },
            ),
        contentAlignment = Alignment.CenterStart,
    ) {
        val size = 14.dp + 4.dp * p
        Box(
            Modifier
                .offset { IntOffset(((4.dp + (18.dp - size) / 2).toPx() + pos.value * travel).toInt(), 0) }
                .size(size)
                .stretchX { stretch }
                .clip(CircleShape)
                .background(lerp(c.muted, Color.White, p)),
        )
    }
}

/**
 * A settings row. With [checked] it is the row of a switch: tapping it flips the switch, and a
 * screen reader hears one thing, the title with on or off, not the row and the switch apart.
 */
@Composable
fun SettingRow(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    onClick: (() -> Unit)? = null,
    checked: Boolean? = null,
    leading: (@Composable () -> Unit)? = null,
    trailing: @Composable () -> Unit = {},
) {
    Row(
        modifier
            .fillMaxWidth()
            .searchHighlight(title)
            .clip(RoundedCornerShape(Radii.row))
            .swipeActions()
            .then(
                when {
                    onClick == null -> Modifier
                    checked != null -> Modifier.toggleable(checked, role = Role.Switch) { onClick() }
                    else -> Modifier.clickable(onClick = onClick)
                },
            )
            .padding(horizontal = 12.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (leading != null) {
            leading()
            Spacer(Modifier.width(12.dp))
        }
        Column(Modifier.weight(1f)) {
            Txt(title, style = Hamstr.type.body)
            if (subtitle != null) {
                Spacer(Modifier.height(2.dp))
                // A little more room between lines: subtitles often run to two or three.
                Txt(subtitle, style = Hamstr.type.small.copy(lineHeight = 17.sp), color = Hamstr.colors.ghost)
            }
        }
        Spacer(Modifier.width(12.dp))
        if (checked != null && onClick != null) Box(Modifier.clearAndSetSemantics {}) { trailing() } else trailing()
    }
}

@Composable
fun Divider(modifier: Modifier = Modifier) {
    Box(
        modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp)
            .height(1.dp)
            .background(Hamstr.colors.line),
    )
}
