package app.hamstr.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.nativeCanvas
import app.hamstr.res.Res
import org.jetbrains.skia.Data
import org.jetbrains.skia.Font
import org.jetbrains.skia.FontMgr
import org.jetbrains.skia.Paint
import org.jetbrains.skia.Typeface

private var loaded: Typeface? = null

@Composable
internal actual fun rememberMonoPaint(): GlyphPaint {
    val typeface by produceState(loaded) {
        if (value == null) value = FontMgr.default.makeFromData(Data.makeFromBytes(Res.readBytes("font/jetbrains_mono_600.ttf")))
            .also { loaded = it }
    }
    return remember(typeface) { SkiaGlyphPaint(typeface) }
}

internal class SkiaGlyphPaint(typeface: Typeface?) : GlyphPaint {
    private val font = Font(typeface, START)
    private val paint = Paint().apply { isAntiAlias = true }
    // Rasters draw thousands of glyphs a second from a handful of characters, so each character's
    // String and width are kept instead of made and measured (shaped) per glyph: indexed by the
    // character (the rasters only use Latin-1), so a lookup boxes nothing. Widths are measured at
    // the size they are drawn at (hinting doesn't scale them linearly), for the last few sizes:
    // the mark draws its cheeks at a second size between the others.
    private val strings = arrayOfNulls<String>(256)
    private val sizes = FloatArray(SLOTS) { if (it == 0) START else Float.NaN }
    private val widths = Array(SLOTS) { FloatArray(256) { Float.NaN } }
    private val baselines = FloatArray(SLOTS) { Float.NaN }
    private var slot = 0
    private var oldest = 1
    // The font's size, kept here: the mark sets one per glyph, mostly the same, and reading or
    // setting it on the font is a call into Skia each time.
    private var size = START
    override var textSize: Float
        get() = size
        set(value) {
            if (value == size) return
            size = value
            font.size = value
            slot = sizes.indexOfFirst { it == value }.takeIf { it >= 0 } ?: oldest.also {
                oldest = (oldest + 1) % SLOTS
                sizes[it] = value
                widths[it].fill(Float.NaN)
                baselines[it] = Float.NaN
            }
        }
    override var color: Int
        get() = paint.color
        set(value) { paint.color = value }
    override val centerBaseline: Float
        get() {
            if (baselines[slot].isNaN()) baselines[slot] = font.metrics.let { -(it.ascent + it.descent) / 2f }
            return baselines[slot]
        }
    override fun draw(canvas: Canvas, text: String, x: Float, y: Float) {
        if (text.length == 1 && text[0].code < strings.size) return draw(canvas, text[0], x, y)
        canvas.nativeCanvas.drawString(text, x - font.measureTextWidth(text) / 2f, y, font, paint)
    }
    override fun draw(canvas: Canvas, glyph: Char, x: Float, y: Float) {
        val i = glyph.code
        if (i >= strings.size) return draw(canvas, glyph.toString(), x, y)
        val s = strings[i] ?: glyph.toString().also { strings[i] = it }
        canvas.nativeCanvas.drawString(s, x - width(glyph) / 2f, y, font, paint)
    }

    /** [glyph]'s width at the current size, measured once per size; Latin-1 only. */
    internal fun width(glyph: Char): Float {
        val i = glyph.code
        val w = widths[slot]
        if (w[i].isNaN()) w[i] = font.measureTextWidth(strings[i] ?: glyph.toString().also { strings[i] = it })
        return w[i]
    }

    private companion object {
        const val SLOTS = 4
        const val START = 12f
    }
}
