package app.hamstr.ui.components

import android.graphics.Paint
import android.graphics.Typeface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.platform.LocalFontFamilyResolver
import androidx.compose.ui.text.font.FontWeight
import app.hamstr.ui.theme.Hamstr

@Composable
internal actual fun rememberMonoPaint(): GlyphPaint {
    // The theme's mono font is already loaded by Compose; on Android its resolver hands back the Typeface.
    val typeface = LocalFontFamilyResolver.current.resolve(Hamstr.type.code, FontWeight.SemiBold).value as? Typeface
    return remember(typeface) { AndroidGlyphPaint(typeface ?: Typeface.MONOSPACE) }
}

private class AndroidGlyphPaint(typeface: Typeface) : GlyphPaint {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        this.typeface = typeface
        textAlign = Paint.Align.CENTER
    }
    override var textSize by paint::textSize
    override var color by paint::color
    override val centerBaseline get() = -(paint.ascent() + paint.descent()) / 2f
    override fun draw(canvas: Canvas, text: String, x: Float, y: Float) = canvas.nativeCanvas.drawText(text, x, y, paint)
}
