package app.hamstr.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import app.hamstr.audio.Channels
import app.hamstr.audio.Equalizer
import app.hamstr.data.Prefs
import app.hamstr.res.*
import app.hamstr.ui.components.ButtonKind
import app.hamstr.ui.components.Chips
import app.hamstr.ui.components.Divider
import app.hamstr.ui.components.HButton
import app.hamstr.ui.theme.Hamstr
import org.jetbrains.compose.resources.stringResource
import kotlin.math.abs
import kotlin.math.log2
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * The equalizer as both apps show it: on or off, the presets, and the ten bands as sliders
 * over the curve they make together. A band is moved by grabbing its knob; touching the rest
 * of the field scrolls the page as usual.
 */
@Composable
fun EqualizerBlock() {
    val s by Prefs.settings.collectAsState()
    SettingBlock(stringResource(Res.string.sound_channels)) {
        val picked = Channels.of(s.soundChannels)
        val titles = Channels.entries.associateWith { stringResource(it.title) }
        val hint = stringResource(picked.hint)
        Chips(Channels.entries, picked, { titles.getValue(it) }, { if (it == picked) hint else null }) { ch ->
            Prefs.edit { it.copy(soundChannels = ch.id) }
        }
    }
    Divider()
    FxRow(stringResource(Res.string.eq_title), stringResource(Res.string.eq_hint), s.eqOn) { v -> Prefs.edit { it.copy(eqOn = v) } }
    AnimatedVisibility(s.eqOn, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
        Column {
            Divider()
            SettingBlock(stringResource(Res.string.eq_presets)) {
                val names = Equalizer.Preset.entries.associate { it.id to stringResource(it.title) } + (Equalizer.CUSTOM to stringResource(Res.string.eq_custom))
                Chips(Equalizer.Preset.entries.map { it.id } + Equalizer.CUSTOM, s.eqPreset, { names[it] ?: it }) { id ->
                    Equalizer.Preset.of(id)?.let(Equalizer::pick) ?: Prefs.edit { it.copy(eqPreset = Equalizer.CUSTOM) }
                }
            }
            EqBands(Modifier.padding(horizontal = 8.dp))
            Row(Modifier.fillMaxWidth().padding(8.dp), horizontalArrangement = Arrangement.End) {
                HButton(
                    stringResource(Res.string.eq_reset), { Equalizer.pick(Equalizer.Preset.Flat) },
                    kind = ButtonKind.Ghost, icon = Res.drawable.ic_restore, enabled = s.eqPreset != Equalizer.Preset.Flat.id,
                )
            }
        }
    }
}

private val LABELS = listOf("31", "62", "125", "250", "500", "1k", "2k", "4k", "8k", "16k")

/** The ten bands: knobs on tracks, the curve behind them, the frequency under each. */
@Composable
private fun EqBands(modifier: Modifier = Modifier) {
    val c = Hamstr.colors
    val type = Hamstr.type
    val s by Prefs.settings.collectAsState()
    val live by Equalizer.live.collectAsState()
    val gains = live ?: Equalizer.gains(s)
    val current by rememberUpdatedState(gains)
    val haptics = LocalHapticFeedback.current
    var held by remember { mutableIntStateOf(-1) }
    val text = rememberTextMeasurer()
    val label = stringResource(Res.string.eq_title)
    // The curve, sampled across the same log scale the bands sit on.
    val points = 96
    val freqs = remember { DoubleArray(points) { 31.25 * 2.0.pow(it * 10.0 / (points - 1) - 0.5) } }
    val curve = remember(gains.toList()) { Equalizer.curve(gains, freqs) }
    Canvas(
        modifier
            .fillMaxWidth()
            .height(212.dp)
            .semantics { contentDescription = label }
            .pointerInput(Unit) {
                val top = 16.dp.toPx()
                val bottom = size.height - 30.dp.toPx()
                val grab = 26.dp.toPx()
                fun yOf(db: Float) = top + (bottom - top) * (1 - (db + Equalizer.MAX_DB) / (2 * Equalizer.MAX_DB))
                fun dbOf(y: Float) = (((1 - (y - top) / (bottom - top)) * 2 - 1) * Equalizer.MAX_DB).coerceIn(-Equalizer.MAX_DB, Equalizer.MAX_DB)
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    val column = size.width / Equalizer.BANDS.size
                    val band = (down.position.x / column).toInt().coerceIn(0, Equalizer.BANDS.lastIndex)
                    // Only the knob is a handle; elsewhere the page scrolls.
                    if (abs(down.position.y - yOf(current[band])) > grab) return@awaitEachGesture
                    down.consume()
                    held = band
                    val start = current.copyOf()
                    val offset = down.position.y - yOf(start[band])
                    var db = start[band]
                    while (true) {
                        val event = awaitPointerEvent()
                        val p = event.changes.firstOrNull { it.id == down.id } ?: break
                        if (!p.pressed) break
                        p.consume()
                        // Half a dB at a time; crossing the middle ticks.
                        val next = (dbOf(p.position.y - offset) * 2).roundToInt() / 2f
                        if (next != db) {
                            if ((next == 0f) || (db < 0f) != (next < 0f) || abs(next) == Equalizer.MAX_DB) haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
                            db = next
                            Equalizer.live.value = start.copyOf().also { it[band] = db }
                        }
                    }
                    held = -1
                    if (db != start[band]) Equalizer.setBand(band, db)
                    Equalizer.live.value = null
                }
            },
    ) {
        val top = 16.dp.toPx()
        val bottom = size.height - 30.dp.toPx()
        val column = size.width / Equalizer.BANDS.size
        fun yOf(db: Double) = (top + (bottom - top) * (1 - (db + Equalizer.MAX_DB) / (2 * Equalizer.MAX_DB))).toFloat()
        val zero = yOf(0.0)
        // The middle line, then the curve over it, filled towards it.
        drawLine(c.line, Offset(0f, zero), Offset(size.width, zero), 1.dp.toPx())
        val line = Path()
        val area = Path().apply { moveTo(0f, zero) }
        freqs.forEachIndexed { i, f ->
            val x = ((log2(f / 31.25) + 0.5) * column).toFloat()
            val y = yOf(curve[i].coerceIn(-Equalizer.MAX_DB.toDouble(), Equalizer.MAX_DB.toDouble()))
            if (i == 0) line.moveTo(x, y) else line.lineTo(x, y)
            area.lineTo(x, y)
        }
        area.lineTo(size.width, zero); area.close()
        drawPath(area, c.accent.copy(alpha = 0.12f))
        drawPath(line, c.accent.copy(alpha = 0.55f), style = Stroke(2.dp.toPx(), cap = StrokeCap.Round))
        Equalizer.BANDS.indices.forEach { i ->
            val x = column * (i + 0.5f)
            drawLine(c.canvas, Offset(x, top), Offset(x, bottom), 4.dp.toPx(), StrokeCap.Round)
            val y = yOf(gains[i].toDouble())
            drawLine(c.accent, Offset(x, zero), Offset(x, y), 4.dp.toPx(), StrokeCap.Round)
            drawCircle(if (gains[i] == 0f) c.muted else c.accent, (if (held == i) 10 else 7).dp.toPx(), Offset(x, y))
            val name = text.measure(LABELS[i], type.mono.copy(color = if (held == i) c.ink else c.ghost))
            drawText(name, topLeft = Offset(x - name.size.width / 2f, size.height - name.size.height - 4.dp.toPx()))
            if (held == i) {
                val v = gains[i]
                val value = text.measure((if (v > 0) "+" else "") + (if (v % 1f == 0f) v.toInt().toString() else v.toString()), type.mono.copy(color = c.ink))
                val vy = if (y - 12.dp.toPx() - value.size.height < 0) y + 14.dp.toPx() else y - 14.dp.toPx() - value.size.height
                drawText(value, topLeft = Offset((x - value.size.width / 2f).coerceIn(0f, size.width - value.size.width), vy))
            }
        }
    }
}

/** The equalizer over the player, the same as in Settings › Playback. */
@Composable
fun EqualizerSheet() {
    app.hamstr.ui.components.Sheet {
        app.hamstr.ui.components.SheetHeader(stringResource(Res.string.eq_title))
        Spacer(Modifier.height(8.dp))
        EqualizerBlock()
    }
}
