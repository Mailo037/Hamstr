package app.hamstr.ui.components

import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.withInfiniteAnimationFrameMillis
import androidx.compose.foundation.Canvas
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
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
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import app.hamstr.ui.theme.Hamstr
import app.hamstr.ui.theme.LocalFx
import kotlinx.coroutines.delay
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin

internal const val NOISE = "+#0*.:-="
private const val RAMP = " .:-=+*#0@"

/** [rgb] (0xRRGGBB) with [alpha] (0..1) as ARGB, for the per-glyph colours: no Color, no colour space. */
private fun argb(rgb: Int, alpha: Float): Int = ((alpha.coerceIn(0f, 1f) * 255f + 0.5f).toInt() shl 24) or (rgb and 0xFFFFFF)

internal fun hash(v: Float): Float {
    val h = sin(v * 127.1f + 311.7f) * 43758.5453f
    return h - floor(h)
}

/** A bitmap reduced to one colour sample per character cell. */
private class Samples(val cols: Int, val rows: Int, val pixels: IntArray) {
    fun lum(i: Int): Float {
        val p = pixels[i]
        return (0.3f * ((p shr 16) and 0xFF) + 0.59f * ((p shr 8) and 0xFF) + 0.11f * (p and 0xFF)) / 255f
    }
}

private fun sample(src: ImageBitmap, cols: Int, rows: Int): Samples {
    // Centre-crop to the cell grid's aspect ratio, then average each cell's pixels.
    val target = cols.toFloat() / rows
    val srcRatio = src.width.toFloat() / src.height
    val (cw, ch) = if (srcRatio > target) (src.height * target).toInt() to src.height else src.width to (src.width / target).toInt()
    val w = max(1, cw)
    val h = max(1, ch)
    val crop = IntArray(w * h)
    src.readPixels(crop, (src.width - w) / 2, (src.height - h) / 2, w, h)
    val px = IntArray(cols * rows)
    for (r in 0 until rows) for (c in 0 until cols) {
        val x0 = c * w / cols
        val x1 = max(x0 + 1, (c + 1) * w / cols)
        val y0 = r * h / rows
        val y1 = max(y0 + 1, (r + 1) * h / rows)
        // At most 4 × 4 samples per cell: enough for a character, cheap for a big picture.
        val sx = max(1, (x1 - x0) / 4)
        val sy = max(1, (y1 - y0) / 4)
        var red = 0
        var green = 0
        var blue = 0
        var n = 0
        var y = y0
        while (y < y1) {
            var x = x0
            while (x < x1) {
                val p = crop[y * w + x]
                red += (p shr 16) and 0xFF
                green += (p shr 8) and 0xFF
                blue += p and 0xFF
                n++
                x += sx
            }
            y += sy
        }
        px[r * cols + c] = (0xFF shl 24) or ((red / n) shl 16) or ((green / n) shl 8) or (blue / n)
    }
    return Samples(cols, rows, px)
}

/**
 * Draws [bitmap] as coloured ASCII characters; the real image is revealed from the
 * top down to [reveal] (0..1) with an accent edge. While [rain] > 0 the cells show a
 * Matrix-style column rain instead (loading state), blending into the image's ASCII.
 */
@Composable
fun AsciiImage(
    bitmap: ImageBitmap?,
    reveal: Float,
    modifier: Modifier = Modifier,
    rain: Float = 0f,
    cell: Dp = 6.dp,
    animate: Boolean = rain > 0f,
) {
    val fx = LocalFx.current
    val accent = Hamstr.colors.accent
    val bg = Hamstr.colors.black
    val arrival = rememberArrival(bitmap)
    // A fully revealed image covers every ASCII cell; skip sampling and glyph drawing.
    if ((bitmap != null && reveal >= 1f) || !fx.asciiThumbs || (!fx.rain && bitmap == null)) {
        PlainImage(bitmap, reveal, modifier, bg, accent, arrival)
        return
    }
    val paint = rememberMonoPaint()
    val cellPx = with(LocalDensity.current) { cell.toPx() }
    // Rain while loading, at 30 steps a second; it stands still while the window is in the background.
    val time by rememberAmbientClock(30, enabled = animate && fx.rain)
    val image: ImageBitmap? = bitmap
    // A plain holder, not state: the first draw fills it, and writing state while drawing
    // would only ask for one more draw.
    val cache = remember(bitmap) { arrayOfNulls<Samples>(1) }
    val columns = remember { RainColumns() }

    Canvas(modifier) {
        drawRect(bg)
        val cols = max(1, (size.width / cellPx).toInt())
        val rows = max(1, (size.height / cellPx).toInt())
        val cw = size.width / cols
        val chh = size.height / rows
        val samples = bitmap?.let { b ->
            cache[0]?.takeIf { it.cols == cols && it.rows == rows } ?: sample(b, cols, rows).also { cache[0] = it }
        }
        val head = accent.toArgb()
        val shown = arrival.value
        paint.textSize = min(cw, chh) * 1.05f
        val sec = time / 1000f
        val canvas = drawContext.canvas
        val baseline = paint.centerBaseline
        // What depends on the column alone (its drop's place and length, its noise's phase) once per column, not per cell.
        if (rain > 0f) columns.at(sec, cols, rows)
        for (r in 0 until rows) for (c in 0 until cols) {
            var rainA = 0f
            var isHead = false
            if (rain > 0f) {
                val trail = columns.trail[c]
                val dist = columns.pos[c] - r
                if (dist >= 0f && dist < trail) {
                    rainA = 1f - dist / trail
                    isHead = dist < 1f
                }
            }
            val i = r * cols + c
            val x = c * cw + cw / 2f
            val y = r * chh + chh / 2f + baseline
            if (rain >= 0.5f || samples == null) {
                if (rainA <= 0f) continue
                val ch = NOISE[(hash(c * 31f + r * 17f + columns.phase[c]) * NOISE.length).toInt().coerceIn(0, NOISE.length - 1)]
                paint.color = if (isHead) head else argb(0xE1DED7, rainA * 0.75f * max(rain, 0.5f))
                paint.draw(canvas, ch, x, y)
            } else {
                val l = samples.lum(i)
                val ch = RAMP[min(RAMP.length - 1, (l * RAMP.length).toInt())]
                if (ch == ' ') continue
                val p = samples.pixels[i]
                val k = 0.55f + l * 0.6f
                val a = min(1f, 1f - rain * 2f + rainA * rain) * shown
                paint.color = ((a * 255).toInt() shl 24) or
                    (min(255, (((p shr 16) and 0xFF) * k + 40).toInt()) shl 16) or
                    (min(255, (((p shr 8) and 0xFF) * k + 40).toInt()) shl 8) or
                    min(255, ((p and 0xFF) * k + 40).toInt())
                paint.draw(canvas, ch, x, y)
            }
        }
        if (image != null && reveal > 0f) {
            val edge = size.height * reveal.coerceIn(0f, 1f)
            clipRect(bottom = edge) { drawCover(image, alpha = shown) }
            if (reveal < 1f) drawRect(accent, Offset(0f, edge - 1.dp.toPx()), Size(size.width, 1.dp.toPx()))
        }
    }
}

/**
 * 1 for a picture that is there from the first frame (it was shown lately, see rememberImage);
 * one that arrives later fades in over Specs.appear instead of cutting in. Read while drawing.
 */
@Composable
private fun rememberArrival(bitmap: ImageBitmap?): State<Float> {
    val has = bitmap != null
    val arrival = remember { Animatable(if (has) 1f else 0f) }
    LaunchedEffect(has) { if (has) arrival.animateTo(1f, Specs.appear()) else arrival.snapTo(0f) }
    return arrival.asState()
}

/** The loading rain's columns at one moment: where each drop is, how long its trail, its noise's phase. */
private class RainColumns {
    var pos = FloatArray(0)
    var trail = FloatArray(0)
    var phase = FloatArray(0)

    fun at(sec: Float, cols: Int, rows: Int) {
        if (pos.size < cols) {
            pos = FloatArray(cols)
            trail = FloatArray(cols)
            phase = FloatArray(cols)
        }
        for (c in 0 until cols) {
            val speed = 7f + hash(c.toFloat()) * 9f
            val t = 6f + hash(c + 50f) * 10f
            trail[c] = t
            pos[c] = (sec * speed + hash(c + 99f) * (rows + t) * 2f) % (rows + t)
            phase[c] = floor(sec * 2f + hash(c.toFloat()) * 9f) * 0.37f
        }
    }
}

/** ASCII effects off: the plain thumbnail, dimmed below the progress edge. */
@Composable
private fun PlainImage(image: ImageBitmap?, reveal: Float, modifier: Modifier, bg: Color, accent: Color, arrival: State<Float>) {
    Canvas(modifier) {
        drawRect(bg)
        if (image == null) return@Canvas
        val shown = arrival.value
        if (reveal >= 1f) {
            drawCover(image, alpha = shown)
            return@Canvas
        }
        drawCover(image, alpha = 0.3f * shown)
        val edge = size.height * reveal.coerceIn(0f, 1f)
        clipRect(bottom = edge) { drawCover(image, alpha = shown) }
        if (reveal in 0.001f..0.999f) drawRect(accent, Offset(0f, edge - 1.dp.toPx()), Size(size.width, 1.dp.toPx()))
    }
}

private fun DrawScope.drawCover(image: ImageBitmap, alpha: Float = 1f) {
    val s = max(size.width / image.width, size.height / image.height)
    val w = image.width * s
    val h = image.height * s
    // Rounded outwards: cut down to whole pixels, the picture left a hairline of the black
    // ground at its edge, which shimmered while a cover slid.
    val x = floor((size.width - w) / 2)
    val y = floor((size.height - h) / 2)
    drawImage(
        image,
        srcOffset = IntOffset.Zero,
        srcSize = IntSize(image.width, image.height),
        dstOffset = IntOffset(x.toInt(), y.toInt()),
        dstSize = IntSize(ceil(size.width - 2 * x).toInt(), ceil(size.height - 2 * y).toInt()),
        alpha = alpha,
    )
}

/**
 * Calm background: a faint character raster under a warm glow, fading out downwards.
 * The only ambient motion in the app (Glyph: "calm when idle"). The raster moves four times a
 * second and is drawn into a bitmap only then; every other frame just shows that bitmap.
 */
@Composable
fun RasterBackground(modifier: Modifier = Modifier) {
    val showRaster = LocalFx.current.raster
    val paint = rememberMonoPaint()
    val accent = Hamstr.colors.accent
    val time by rememberAmbientClock(4, enabled = showRaster)
    val cache = remember { RasterCache() }
    Spacer(
        modifier.drawWithCache {
            val raster = if (showRaster) cache.draw(size, paint, time.toFloat(), 13.dp.toPx()) else null
            val glow = androidx.compose.ui.graphics.Brush.radialGradient(
                listOf(accent.copy(alpha = 0.16f), Color.Transparent),
                center = Offset(size.width * 0.3f, -size.height * 0.1f),
                radius = size.height * 1.1f,
            )
            onDrawBehind {
                drawRect(glow)
                raster?.let { drawImage(it) }
            }
        },
    )
}

/** The raster's bitmap, kept while the size stays and cleared before each redraw. */
private class RasterCache {
    private var image: ImageBitmap? = null
    private val clear = Paint().apply { blendMode = BlendMode.Clear }

    fun draw(size: Size, paint: GlyphPaint, t: Float, cell: Float): ImageBitmap? {
        val w = size.width.toInt()
        val h = size.height.toInt()
        if (w <= 0 || h <= 0) return null
        val img = image?.takeIf { it.width == w && it.height == h } ?: ImageBitmap(w, h).also { image = it }
        val canvas = androidx.compose.ui.graphics.Canvas(img)
        canvas.drawRect(Rect(0f, 0f, w.toFloat(), h.toFloat()), clear)
        paint.textSize = cell * 0.8f
        var r = 0
        while (r * cell < h) {
            val fade = (1f - r * cell / h).pow(2.2f)
            var c = 0
            while (c * cell < w) {
                val v = (sin(c * 0.55f + t / 1800f) + sin(r * 0.7f - t / 2300f) + sin((c + r) * 0.3f + t / 3100f)) / 3f
                val a = fade * (0.05f + max(0f, v) * 0.16f)
                if (a >= 0.02f) {
                    paint.color = argb(0xE6E1D7, a)
                    paint.draw(canvas, if (v > 0.45f) '+' else if (v > 0.2f) '·' else '.', c * cell + cell / 2, r * cell + cell / 2)
                }
                c++
            }
            r++
        }
        return img
    }
}

/**
 * Text that decodes in from ASCII noise, left to right (Glyph's "text arrives"). Only when it
 * really arrives: a text that already decoded in this run of the app (a tab coming back, a row
 * scrolling in again, the player opening on the same song) shows as it is, and decodes again only
 * when it changes while on screen. [once] = false decodes every time it comes on screen (the
 * tour's pictures, which show the effect).
 */
@Composable
fun DecodeText(
    text: String,
    style: TextStyle = Hamstr.type.body,
    color: Color = Hamstr.colors.ink,
    modifier: Modifier = Modifier,
    maxLines: Int = 1,
    durationMs: Int = 520,
    delayMs: Long = 0,
    once: Boolean = true,
) {
    if (!LocalFx.current.decode) {
        Txt(text, style = style, color = color, modifier = modifier, maxLines = maxLines)
        return
    }
    val known = remember { once && Decoded.has(text) }
    // Otherwise it starts as noise of the same length, so a delayed decode never flashes the final text.
    var shown by remember { mutableStateOf(if (known) text else text.map { if (it == ' ') ' ' else NOISE.random() }.joinToString("")) }
    val first = remember { booleanArrayOf(true) }
    LaunchedEffect(text) {
        val skip = first[0] && known
        first[0] = false
        if (skip) {
            shown = text
            return@LaunchedEffect
        }
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
        if (once) Decoded.add(text)
    }
    // Screen readers get the real title, not the noise on its way there.
    Txt(shown, style = style, color = color, modifier = modifier.clearAndSetSemantics { contentDescription = text }, maxLines = maxLines)
}

/** The texts that decoded in this run of the app, the last few hundred of them. */
private object Decoded {
    private const val KEEP = 512
    private val texts = LinkedHashSet<String>()

    @Synchronized
    fun has(text: String) = text in texts

    @Synchronized
    fun add(text: String) {
        texts.remove(text)
        texts.add(text)
        if (texts.size > KEEP) texts.remove(texts.first())
    }
}
