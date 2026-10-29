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

private class SkiaGlyphPaint(typeface: Typeface?) : GlyphPaint {
    private val font = Font(typeface, 12f)
    private val paint = Paint().apply { isAntiAlias = true }
    override var textSize: Float
        get() = font.size
        set(value) { font.size = value }
    override var color: Int
        get() = paint.color
        set(value) { paint.color = value }
    override val centerBaseline get() = font.metrics.let { -(it.ascent + it.descent) / 2f }
    override fun draw(canvas: Canvas, text: String, x: Float, y: Float) {
        canvas.nativeCanvas.drawString(text, x - font.measureTextWidth(text) / 2f, y, font, paint)
    }
}
