package app.hamstr.ui.components

import org.jetbrains.skia.Font
import kotlin.test.Test
import kotlin.test.assertEquals

class GlyphPaintTest {
    /** What Skia measures for [glyph] at [size], the way the cache is meant to remember it. */
    private fun measured(glyph: Char, size: Float) = Font(null, size).measureTextWidth(glyph.toString())

    @Test
    fun widthsAreThoseOfTheSizeTheyAreDrawnAt() {
        val paint = SkiaGlyphPaint(null)
        // More sizes than the cache keeps, and back: each width is measured at its own size again.
        val sizes = listOf(12f, 7.3f, 13.65f, 21f, 9f, 7.3f, 12f, 30.5f, 13.65f)
        repeat(2) {
            for (size in sizes) {
                paint.textSize = size
                for (glyph in "+.·#0@ ") assertEquals(measured(glyph, size), paint.width(glyph), "'$glyph' at $size")
            }
        }
    }

    @Test
    fun theBaselineFollowsTheSize() {
        val paint = SkiaGlyphPaint(null)
        for (size in listOf(10f, 20f, 10f, 31f, 44f, 52f, 10f)) {
            paint.textSize = size
            val metrics = Font(null, size).metrics
            assertEquals(-(metrics.ascent + metrics.descent) / 2f, paint.centerBaseline, "at $size")
        }
    }
}
