package app.hamstr.ui.components

import androidx.compose.foundation.gestures.Orientation
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import app.hamstr.data.AccentPreset
import app.hamstr.ui.theme.Hamstr
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * Accent picker: preset swatches that pop when chosen, plus a hue slider for any colour.
 * The slider thumb stretches with its speed and the whole app recolours live.
 */
@Composable
fun AccentPicker(presets: List<AccentPreset>, accent: Int, onPick: (Int) -> Unit) {
    val c = Hamstr.colors
    val haptics = LocalHapticFeedback.current
    Column {
        val scroll = rememberScrollState()
        Row(
            Modifier.edgeFade(scroll, Orientation.Horizontal).horizontalScroll(scroll).padding(vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            presets.forEach { p ->
                val on = p.argb == accent
                val scale by animateFloatAsState(if (on) 1.12f else 1f, spring(dampingRatio = 0.4f, stiffness = 500f), label = "swatch")
                Box(
                    Modifier
                        .size(40.dp)
                        .graphicsLayer { scaleX = scale; scaleY = scale }
                        .clip(CircleShape)
                        .border(if (on) 2.dp else 0.dp, if (on) c.ink else Color.Transparent, CircleShape)
                        .padding(if (on) 4.dp else 0.dp)
                        .clip(CircleShape)
                        .background(Color(p.argb))
                        .clickable(remember { MutableInteractionSource() }, null) {
                            haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
                            onPick(p.argb)
                        },
                )
            }
        }
        Spacer(Modifier.height(14.dp))
        HueSlider(accent, onPick)
    }
}

private val HUES = List(13) { Color.hsv(it * 30f % 360f, 0.72f, 1f) }

@Composable
private fun HueSlider(accent: Int, onPick: (Int) -> Unit) {
    val c = Hamstr.colors
    val scope = rememberCoroutineScope()
    val pick by rememberUpdatedState(onPick)
    val hsv = remember(accent) { FloatArray(3).also { android.graphics.Color.colorToHSV(accent, it) } }
    val thumbDp = 26.dp
    BoxWithConstraints(Modifier.fillMaxWidth().height(34.dp)) {
        val thumb = with(LocalDensity.current) { thumbDp.toPx() }
        val track = constraints.maxWidth - thumb
        val x = remember { Animatable(hsv[0] / 360f * track) }
        var dragging by remember { mutableStateOf(false) }
        LaunchedEffect(accent, track) { if (!dragging) x.animateTo(hsv[0] / 360f * track, spring(dampingRatio = 0.6f, stiffness = 500f)) }
        val stretch by rememberVelocityStretch({ x.value }, sensitivity = 0.00016f, max = 0.4f)

        fun emit(px: Float) {
            val hue = (px / track).coerceIn(0f, 1f) * 360f
            pick(Color.hsv(hue % 360f, 0.72f, 1f).toArgb())
        }

        Canvas(
            Modifier
                .fillMaxWidth()
                .height(12.dp)
                .align(Alignment.Center)
                .pointerInput(track) {
                    detectTapGestures { pos ->
                        val px = (pos.x - thumb / 2).coerceIn(0f, track)
                        scope.launch { x.animateTo(px, spring(dampingRatio = 0.6f, stiffness = 500f)) }
                        emit(px)
                    }
                },
        ) {
            drawRoundRect(Brush.horizontalGradient(HUES), cornerRadius = CornerRadius(size.height / 2))
        }
        Box(
            Modifier
                .offset { IntOffset(x.value.roundToInt(), 0) }
                .size(thumbDp)
                .align(Alignment.CenterStart)
                .stretchX { stretch }
                .clip(CircleShape)
                .background(c.ink)
                .padding(4.dp)
                .clip(CircleShape)
                .background(Color(accent))
                .pointerInput(track) {
                    detectDragGestures(
                        onDragStart = { dragging = true },
                        onDragEnd = { dragging = false },
                        onDragCancel = { dragging = false },
                    ) { change, drag ->
                        change.consume()
                        val px = (x.value + drag.x).coerceIn(0f, track)
                        scope.launch { x.snapTo(px) }
                        emit(px)
                    }
                },
        )
    }
}
