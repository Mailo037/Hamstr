package app.hamstr

import app.hamstr.res.*
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.animation.core.Animatable
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import app.hamstr.ui.components.DecodeText
import app.hamstr.ui.components.DragPillRow
import app.hamstr.ui.components.HamstrMark
import app.hamstr.ui.components.MarkState
import kotlinx.coroutines.delay
import app.hamstr.ui.components.Icon
import app.hamstr.ui.components.Txt
import app.hamstr.ui.theme.Hamstr
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.foundation.layout.offset
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.IntOffset
import app.hamstr.ui.components.LocalReduceMotion
import kotlin.math.abs
import kotlin.math.roundToInt
import app.hamstr.ui.components.Specs

/** Bottom tab bar: tap a tab or drag the pill along; it stretches with its speed. */
@Composable
internal fun TabBar(selected: Tab, onSelect: (Tab) -> Unit, modifier: Modifier = Modifier) {
    val c = Hamstr.colors
    DragPillRow(
        count = Tab.entries.size,
        selected = selected.ordinal,
        onSelect = { onSelect(Tab.entries[it]) },
        itemHeight = 54.dp,
        pillRadius = 17.dp,
        pillColor = c.hover,
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(22.dp))
            .background(c.overlay)
            .padding(5.dp),
    ) { i, near ->
        val t = Tab.entries[i]
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.graphicsLayer {
                val s = 1f + near * 0.06f
                scaleX = s; scaleY = s
                translationY = -near * 1.5f
            },
        ) {
            Icon(t.icon, tint = lerp(c.ghost, c.ink, near), size = 22.dp)
            Spacer(Modifier.height(3.dp))
            Txt(stringResource(t.label), style = Hamstr.type.small, color = lerp(c.ghost, c.ink, near), maxLines = 1)
        }
    }
}

/** Width of the side bar that holds the tabs and the mini player in landscape. */
internal val SIDEBAR = 216.dp

/** The tabs as a column for the side bar; the pill springs to the chosen one. */
@Composable
internal fun SideTabs(selected: Tab, onSelect: (Tab) -> Unit, modifier: Modifier = Modifier) {
    val c = Hamstr.colors
    val haptics = LocalHapticFeedback.current
    val itemHeight = 48.dp
    val y by animateFloatAsState(
        selected.ordinal.toFloat(),
        if (LocalReduceMotion.current) snap() else Specs.arrive(),
        label = "sideTab",
    )
    Box(modifier) {
        Box(
            Modifier
                .offset { IntOffset(0, (y * itemHeight.toPx()).roundToInt()) }
                .fillMaxWidth()
                .height(itemHeight)
                .clip(RoundedCornerShape(17.dp))
                .background(c.hover),
        )
        Column {
            Tab.entries.forEach { t ->
                val near = (1f - abs(y - t.ordinal)).coerceIn(0f, 1f)
                Row(
                    Modifier
                        .fillMaxWidth()
                        .height(itemHeight)
                        .clip(RoundedCornerShape(17.dp))
                        .clickable(remember { MutableInteractionSource() }, null, role = Role.Tab) {
                            if (t != selected) haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
                            onSelect(t)
                        }
                        .padding(horizontal = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(t.icon, tint = lerp(c.ghost, c.ink, near), size = 22.dp)
                    Spacer(Modifier.width(14.dp))
                    Txt(stringResource(t.label), style = Hamstr.type.bodyMedium, color = lerp(c.ghost, c.ink, near), maxLines = 1)
                }
            }
        }
    }
}

/**
 * Cold-start intro: the hamster assembles out of ASCII noise, hops and winks, then the
 * screen fades away into the app. Tap to skip.
 */
@Composable
internal fun Intro(onDone: () -> Unit) {
    val c = Hamstr.colors
    val mark = remember { MarkState(intro = 0f) }
    val fade = remember { Animatable(1f) }
    val done by rememberUpdatedState(onDone)
    var skip by remember { mutableStateOf(false) }
    LaunchedEffect(skip) {
        if (!skip) {
            mark.assemble(700)
            delay(260)
        }
        fade.animateTo(0f, tween(320))
        done()
    }
    Box(
        Modifier
            .fillMaxSize()
            .graphicsLayer { alpha = fade.value }
            .background(c.canvas)
            .clickable(remember { MutableInteractionSource() }, null) { skip = true },
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.graphicsLayer {
                val s = 0.9f + 0.1f * fade.value
                scaleX = s; scaleY = s
            },
        ) {
            HamstrMark(150.dp, state = mark, background = false, interactive = false)
            Spacer(Modifier.height(18.dp))
            DecodeText("Hamstr", style = Hamstr.type.title, delayMs = 380)
        }
    }
}
