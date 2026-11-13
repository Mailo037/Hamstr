package app.hamstr.ui.components

import org.jetbrains.compose.resources.DrawableResource
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.hamstr.Platform
import app.hamstr.ui.theme.Hamstr
import app.hamstr.ui.theme.Radii
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sign

data class SwipeAction(
    val icon: DrawableResource,
    val label: String,
    val color: Color,
    /** The row flies out instead of springing back (it is about to leave the list). */
    val removes: Boolean = false,
    val onTrigger: () -> Unit,
)

/** The actions of the [SwipeRow] around a row; [swipeActions] hands them to screen readers. */
val LocalSwipeActions = compositionLocalOf<List<SwipeAction>> { emptyList() }

/**
 * Rows the pointer's way: a row's actions as icons instead of swipes, its menu on a right-click
 * instead of a button in sight. [Platform.desktop], unless a picture of the phone is drawn on the
 * computer (the release pictures).
 */
val LocalDesktop = staticCompositionLocalOf { Platform.desktop }

/**
 * A screen reader can't swipe: on the row's clickable root this offers the actions of the
 * [SwipeRow] around it as the row's own actions (TalkBack's actions menu, VoiceOver's rotor).
 */
@Composable
fun Modifier.swipeActions(): Modifier {
    val actions = LocalSwipeActions.current
    if (actions.isEmpty()) return this
    return semantics { customActions = actions.map { a -> CustomAccessibilityAction(a.label) { a.onTrigger(); true } } }
}

/**
 * Swipe a row sideways to act. The row tilts and squashes with the drag speed, the
 * action underneath grows until it arms (with a haptic tick), and past that point the
 * drag rubber-bands. Releasing armed runs the action; otherwise the row goes back to rest
 * without swinging past it, so the other side's action never flashes.
 *
 * While it moves, the row carries [background] (what is behind it at rest), so it slides
 * over the action like a card instead of showing it through; the action's name only
 * shows once there is room for all of it.
 *
 * With [icons] (on the desktop, where nobody swipes with a mouse) the actions sit as icons at
 * the row's end instead; with [iconsOnHover] too, only while the pointer is on the row or the
 * keyboard's focus is in it, so a long list stays calm. A screen reader gets them as the row's
 * actions: the row's clickable carries [swipeActions].
 */
@Composable
fun SwipeRow(
    start: SwipeAction?,
    end: SwipeAction?,
    modifier: Modifier = Modifier,
    background: Color = Hamstr.colors.canvas,
    icons: Boolean = LocalDesktop.current,
    iconsOnHover: Boolean = false,
    content: @Composable () -> Unit,
) {
    if (icons) {
        val source = remember { MutableInteractionSource() }
        val hovered by source.collectIsHoveredAsState()
        var focused by remember { mutableStateOf(false) }
        val shown by animateFloatAsState(if (!iconsOnHover || hovered || focused) 1f else 0f, tween(120), label = "rowIcons")
        Row(
            modifier.hoverable(source).onFocusChanged { focused = it.hasFocus },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.weight(1f)) { content() }
            for (a in listOfNotNull(start, end)) {
                IconButton(a.icon, a.label, a.onTrigger, tint = a.color, background = Color.Transparent, modifier = Modifier.graphicsLayer { alpha = shown })
            }
        }
        return
    }
    val scope = rememberCoroutineScope()
    val haptics = LocalHapticFeedback.current
    val startAction by rememberUpdatedState(start)
    val endAction by rememberUpdatedState(end)
    val offset = remember { Animatable(0f) }
    var width by remember { mutableIntStateOf(1) }
    var raw by remember { mutableFloatStateOf(0f) }
    var armed by remember { mutableStateOf(false) }
    val stretch by rememberVelocityStretch({ offset.value }, sensitivity = 0.00006f, max = 0.12f)
    val pop = remember { Animatable(0f) }
    val back = Specs.release<Float>()
    var labelWidth by remember { mutableIntStateOf(0) }
    // The edge padding, the icon and the gap before the name, plus a little air.
    val room = with(LocalDensity.current) { 60.dp.toPx() }
    val fade = with(LocalDensity.current) { 20.dp.toPx() }

    Box(modifier.onSizeChanged { width = it.width }) {
        val o = offset.value
        val threshold = width * 0.26f
        val action = when {
            o > 0f -> start
            o < 0f -> end
            else -> null
        }
        if (action != null) {
            val progress = (abs(o) / threshold).coerceIn(0f, 1f)
            Box(
                Modifier
                    .matchParentSize()
                    .clip(RoundedCornerShape(Radii.row))
                    .background(lerp(Hamstr.colors.overlay, action.color.copy(alpha = 0.28f), progress)),
                contentAlignment = if (o > 0f) Alignment.CenterStart else Alignment.CenterEnd,
            ) {
                Row(
                    Modifier
                        .padding(horizontal = 22.dp)
                        .graphicsLayer {
                            val s = 0.55f + 0.45f * progress + pop.value * 0.25f
                            scaleX = s; scaleY = s
                            alpha = progress
                        },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    val label = Modifier
                        .onSizeChanged { labelWidth = it.width }
                        .graphicsLayer { alpha = ((abs(offset.value) - room - labelWidth) / fade).coerceIn(0f, 1f) }
                    if (o < 0f) {
                        Txt(action.label, style = Hamstr.type.small, color = action.color, maxLines = 1, modifier = label)
                        Spacer(Modifier.width(8.dp))
                    }
                    Icon(action.icon, tint = action.color, size = 22.dp)
                    if (o > 0f) {
                        Spacer(Modifier.width(8.dp))
                        Txt(action.label, style = Hamstr.type.small, color = action.color, maxLines = 1, modifier = label)
                    }
                }
            }
        }
        Box(
            Modifier
                .graphicsLayer {
                    translationX = offset.value
                    val s = stretch
                    rotationZ = s * 14f
                    scaleY = 1f - abs(s) * 0.5f
                }
                .drawBehind { if (offset.value != 0f) drawRoundRect(background, cornerRadius = CornerRadius(Radii.row.toPx())) }
                .pointerInput(Unit) {
                    val tracker = VelocityTracker()

                    // Back to rest, bounded so the spring can't cross it and tint in the other side's
                    // action; a flick towards rest carries on with its speed.
                    fun toRest(withVelocity: Boolean) {
                        val from = offset.value
                        val v = if (withVelocity) tracker.calculateVelocity().x.takeIf { sign(it) == -sign(from) } ?: 0f else 0f
                        scope.launch {
                            offset.updateBounds(min(0f, from), max(0f, from))
                            offset.animateTo(0f, back, initialVelocity = v)
                        }
                    }

                    detectHorizontalDragGestures(
                        onDragStart = {
                            tracker.resetTracking()
                            offset.updateBounds(null, null)
                            raw = offset.value
                            scope.launch { offset.stop() }
                        },
                        onHorizontalDrag = { change, dx ->
                            change.consume()
                            raw += dx
                            val dir = if (raw > 0f) startAction else endAction
                            val limit = width * 0.26f
                            val shown = if (dir == null) raw * 0.12f else rubberBand(raw, limit * 1.15f, 0.5f)
                            tracker.addPosition(change.uptimeMillis, Offset(shown, 0f))
                            scope.launch { offset.snapTo(shown) }
                            val nowArmed = dir != null && abs(shown) >= limit
                            if (nowArmed != armed) {
                                armed = nowArmed
                                haptics.performHapticFeedback(if (nowArmed) HapticFeedbackType.GestureThresholdActivate else HapticFeedbackType.SegmentTick)
                                if (nowArmed) scope.launch { pop.snapTo(1f); pop.animateTo(0f, Specs.pop()) }
                            }
                        },
                        onDragEnd = {
                            val act = if (offset.value > 0f) startAction else endAction
                            val fire = armed && act != null
                            armed = false
                            if (fire && act != null) {
                                haptics.performHapticFeedback(HapticFeedbackType.Confirm)
                                if (act.removes) scope.launch {
                                    val out = sign(offset.value) * width * 1.15f
                                    offset.animateTo(out, Specs.leave(180))
                                    act.onTrigger()
                                    // The row leaves the list now and fades where it flew to; that ends
                                    // this with it. Still here a moment later (the action kept it), it
                                    // slides back.
                                    delay(750)
                                    offset.updateBounds(min(0f, out), max(0f, out))
                                    offset.animateTo(0f, Specs.enter())
                                } else {
                                    act.onTrigger()
                                    toRest(withVelocity = false)
                                }
                            } else toRest(withVelocity = true)
                        },
                        onDragCancel = { armed = false; toRest(withVelocity = false) },
                    )
                },
        ) { CompositionLocalProvider(LocalSwipeActions provides listOfNotNull(start, end)) { content() } }
    }
}
