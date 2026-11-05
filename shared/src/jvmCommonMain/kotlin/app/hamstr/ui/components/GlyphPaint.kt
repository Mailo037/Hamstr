package app.hamstr.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Canvas

/**
 * Draws single JetBrains Mono characters straight onto the platform canvas. The raster,
 * ASCII thumbnails and the mark redraw hundreds of them a frame, so this skips text layout.
 */
interface GlyphPaint {
    var textSize: Float
    /** ARGB. */
    var color: Int
    /** From a glyph's vertical centre down to its baseline, at the current [textSize]. */
    val centerBaseline: Float
    /** Draws [text] centred on [x], with its baseline at [y]. */
    fun draw(canvas: Canvas, text: String, x: Float, y: Float)
    /** Draws one character like [draw], without making a String for it: the hot path of every raster. */
    fun draw(canvas: Canvas, glyph: Char, x: Float, y: Float)
}

@Composable
internal expect fun rememberMonoPaint(): GlyphPaint
