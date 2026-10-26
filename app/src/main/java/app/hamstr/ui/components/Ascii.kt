package app.hamstr.ui.components

import android.graphics.Bitmap
import android.graphics.Paint
import android.graphics.Typeface
import androidx.compose.animation.core.withInfiniteAnimationFrameMillis
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.core.content.res.ResourcesCompat
import app.hamstr.R
import app.hamstr.ui.theme.Hamstr
import app.hamstr.ui.theme.LocalFx
import kotlinx.coroutines.delay
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin

internal const val NOISE = "+#0*.:-="
private const val RAMP = " .:-=+*#0@"

internal fun hash(v: Float): Float {
    val h = sin(v * 127.1f + 311.7f) * 43758.5453f
    return h - floor(h)
}

@Composable
internal fun rememberMonoPaint(): Paint {
    val context = LocalContext.current
    return remember {
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = ResourcesCompat.getFont(context, R.font.jetbrains_mono_600) ?: Typeface.MONOSPACE
            textAlign = Paint.Align.CENTER
        }
    }
}

/** A bitmap reduced to one colour sample per character cell. */
private class Samples(val cols: Int, val rows: Int, val pixels: IntArray) {
    fun lum(i: Int): Float {
        val p = pixels[i]
        return (0.3f * ((p shr 16) and 0xFF) + 0.59f * ((p shr 8) and 0xFF) + 0.11f * (p and 0xFF)) / 255f
    }
}

private fun sample(src: Bitmap, cols: Int, rows: Int): Samples {
    // Centre-crop to the cell grid's aspect ratio, then scale down.
    val target = cols.toFloat() / rows
    val srcRatio = src.width.toFloat() / src.height
    val (cw, ch) = if (srcRatio > target) (src.height * target).toInt() to src.height else src.width to (src.width / target).toInt()
    val cropped = Bitmap.createBitmap(src, (src.width - cw) / 2, (src.height - ch) / 2, max(1, cw), max(1, ch))
    val small = Bitmap.createScaledBitmap(cropped, cols, rows, true)
    val px = IntArray(cols * rows)
    small.getPixels(px, 0, cols, 0, 0, cols, rows)
    return Samples(cols, rows, px)
}

/**
 * Draws [bitmap] as coloured ASCII characters; the real image is revealed from the
 * top down to [reveal] (0..1) with an accent edge. While [rain] > 0 the cells show a
 * Matrix-style column rain instead (loading state), blending into the image's ASCII.
 */
@Composable
fun AsciiImage(
    bitmap: Bitmap?,
    reveal: Float,
    modifier: Modifier = Modifier,
    rain: Float = 0f,
    cell: Dp = 6.dp,
    animate: Boolean = rain > 0f,
) {
    val fx = LocalFx.current
    val paint = rememberMonoPaint()
    val accent = Hamstr.colors.accent
    val bg = Hamstr.colors.black
    if (!fx.asciiThumbs || (!fx.rain && bitmap == null)) {
        PlainImage(bitmap, reveal, modifier, bg, accent)
        return
    }
    val cellPx = with(LocalDensity.current) { cell.toPx() }
    var time by remember { mutableLongStateOf(0L) }
    if (animate) {
        LaunchedEffect(Unit) { while (true) withInfiniteAnimationFrameMillis { time = it } }
    }
    val image: ImageBitmap? = remember(bitmap) { bitmap?.asImageBitmap() }
    var cache by remember(bitmap) { mutableStateOf<Samples?>(null) }

    Canvas(modifier) {
        drawRect(bg)
        val cols = max(1, (size.width / cellPx).toInt())
        val rows = max(1, (size.height / cellPx).toInt())
        val cw = size.width / cols
        val chh = size.height / rows
        val samples = bitmap?.let { b ->
            cache?.takeIf { it.cols == cols && it.rows == rows } ?: sample(b, cols, rows).also { cache = it }
        }
        paint.textSize = min(cw, chh) * 1.05f
        val sec = time / 1000f
        val canvas = drawContext.canvas.nativeCanvas
        val baseline = -(paint.ascent() + paint.descent()) / 2f
        for (r in 0 until rows) for (c in 0 until cols) {
            var rainA = 0f
            var head = false
            if (rain > 0f) {
                val speed = 7f + hash(c.toFloat()) * 9f
                val trail = 6f + hash(c + 50f) * 10f
                val pos = (sec * speed + hash(c + 99f) * (rows + trail) * 2f) % (rows + trail)
                val dist = pos - r
                if (dist >= 0f && dist < trail) {
                    rainA = 1f - dist / trail
                    head = dist < 1f
                }
            }
            val i = r * cols + c
            val x = c * cw + cw / 2f
            val y = r * chh + chh / 2f + baseline
            if (rain >= 0.5f || samples == null) {
                if (rainA <= 0f) continue
                val ch = NOISE[(hash(c * 31f + r * 17f + floor(sec * 2f + hash(c.toFloat()) * 9f) * 0.37f) * NOISE.length).toInt().coerceIn(0, NOISE.length - 1)]
                paint.color = if (head) accent.toArgb() else Color(0xFFE1DED7).copy(alpha = rainA * 0.75f * max(rain, 0.5f)).toArgb()
                canvas.drawText(ch.toString(), x, y, paint)
            } else {
                val l = samples.lum(i)
                val ch = RAMP[min(RAMP.length - 1, (l * RAMP.length).toInt())]
                if (ch == ' ') continue
                val p = samples.pixels[i]
                val k = 0.55f + l * 0.6f
                val a = min(1f, 1f - rain * 2f + rainA * rain)
                paint.color = android.graphics.Color.argb(
                    (a * 255).toInt(),
                    min(255, (((p shr 16) and 0xFF) * k + 40).toInt()),
                    min(255, (((p shr 8) and 0xFF) * k + 40).toInt()),
                    min(255, ((p and 0xFF) * k + 40).toInt()),
                )
                canvas.drawText(ch.toString(), x, y, paint)
            }
        }
        if (image != null && reveal > 0f) {
            val edge = size.height * reveal.coerceIn(0f, 1f)
            clipRect(bottom = edge) { drawCover(image) }
            if (reveal < 1f) drawRect(accent, Offset(0f, edge - 1.dp.toPx()), Size(size.width, 1.dp.toPx()))
        }
    }
}

/** ASCII effects off: the plain thumbnail, dimmed below the progress edge. */
@Composable
private fun PlainImage(bitmap: Bitmap?, reveal: Float, modifier: Modifier, bg: Color, accent: Color) {
    val image = remember(bitmap) { bitmap?.asImageBitmap() }
    Canvas(modifier) {
        drawRect(bg)
        if (image == null) return@Canvas
        drawCover(image, alpha = 0.3f)
        val edge = size.height * reveal.coerceIn(0f, 1f)
        clipRect(bottom = edge) { drawCover(image) }
        if (reveal in 0.001f..0.999f) drawRect(accent, Offset(0f, edge - 1.dp.toPx()), Size(size.width, 1.dp.toPx()))
    }
}

private fun DrawScope.drawCover(image: ImageBitmap, alpha: Float = 1f) {
    val s = max(size.width / image.width, size.height / image.height)
    val w = image.width * s
    val h = image.height * s
    drawImage(
        image,
        srcOffset = IntOffset.Zero,
        srcSize = IntSize(image.width, image.height),
        dstOffset = IntOffset(((size.width - w) / 2).toInt(), ((size.height - h) / 2).toInt()),
        dstSize = IntSize(w.toInt(), h.toInt()),
        alpha = alpha,
    )
}

/**
 * Calm background: a faint character raster under a warm glow, fading out downwards.
 * The only ambient motion in the app (Glyph: "calm when idle").
 */
@Composable
fun RasterBackground(modifier: Modifier = Modifier) {
    val showRaster = LocalFx.current.raster
    val paint = rememberMonoPaint()
    val accent = Hamstr.colors.accent
    var time by remember { mutableLongStateOf(0L) }
    if (showRaster && !LocalReduceMotion.current) LaunchedEffect(Unit) {
        while (true) {
            withInfiniteAnimationFrameMillis { time = it }
            delay(66) // ~15 fps is plenty for a slow drift
        }
    }
    Canvas(modifier) {
        drawRect(
            androidx.compose.ui.graphics.Brush.radialGradient(
                listOf(accent.copy(alpha = 0.16f), Color.Transparent),
                center = Offset(size.width * 0.3f, -size.height * 0.1f),
                radius = size.height * 1.1f,
            ),
        )
        if (!showRaster) return@Canvas
        val cell = 13.dp.toPx()
        paint.textSize = cell * 0.8f
        val canvas = drawContext.canvas.nativeCanvas
        val t = time.toFloat()
        var r = 0
        while (r * cell < size.height) {
            val fade = (1f - r * cell / size.height).pow(2.2f)
            var c = 0
            while (c * cell < size.width) {
                val v = (sin(c * 0.55f + t / 1800f) + sin(r * 0.7f - t / 2300f) + sin((c + r) * 0.3f + t / 3100f)) / 3f
                val a = fade * (0.05f + max(0f, v) * 0.16f)
                if (a >= 0.02f) {
                    paint.color = Color(0xFFE6E1D7).copy(alpha = a).toArgb()
                    canvas.drawText(if (v > 0.45f) "+" else if (v > 0.2f) "·" else ".", c * cell + cell / 2, r * cell + cell / 2, paint)
                }
                c++
            }
            r++
        }
    }
}

/** Text that decodes in from ASCII noise, left to right (Glyph's "text arrives"). */
@Composable
fun DecodeText(
    text: String,
    style: TextStyle = Hamstr.type.body,
    color: Color = Hamstr.colors.ink,
    modifier: Modifier = Modifier,
    maxLines: Int = 1,
    durationMs: Int = 520,
    delayMs: Long = 0,
) {
    // Starts as noise of the same length, so a delayed decode never flashes the final text.
    if (!LocalFx.current.decode) {
        Txt(text, style = style, color = color, modifier = modifier, maxLines = maxLines)
        return
    }
    var shown by remember { mutableStateOf(text.map { if (it == ' ') ' ' else NOISE.random() }.joinToString("")) }
    LaunchedEffect(text) {
        delay(delayMs)
        val start = System.nanoTime()
        while (true) {
            val p = ((System.nanoTime() - start) / 1e6f / durationMs).coerceAtMost(1f)
            val n = text.length
            shown = buildString {
                text.forEachIndexed { i, ch ->
                    append(if (ch == ' ' || i < p * n) ch else NOISE.random())
                }
            }
            if (p >= 1f) break
            withInfiniteAnimationFrameMillis { }
        }
    }
    Txt(shown, style = style, color = color, modifier = modifier, maxLines = maxLines)
}
