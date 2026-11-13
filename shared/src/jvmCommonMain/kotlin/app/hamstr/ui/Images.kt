package app.hamstr.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.graphics.ImageBitmap
import coil3.Image
import coil3.SingletonImageLoader
import coil3.compose.LocalPlatformContext
import coil3.request.ImageRequest
import coil3.request.SuccessResult

/**
 * Loads a thumbnail as a bitmap whose pixels can be read (the ASCII renderer samples them). One
 * that was shown lately is there in the first frame ([Thumbs]); AsciiImage fades in the others.
 */
@Composable
fun rememberImage(url: String?, sizePx: Int = 480): ImageBitmap? {
    val context = LocalPlatformContext.current
    val key = url?.let { "$it@$sizePx" }
    val loaded by produceState<Pair<String, ImageBitmap>?>(key?.let { k -> Thumbs.get(k)?.let { k to it } }, key) {
        if (key == null || Thumbs.get(key) != null) return@produceState
        val request = ImageRequest.Builder(context)
            .data(url)
            .size(sizePx)
            .readablePixels()
            .build()
        val image = (SingletonImageLoader.get(context).execute(request) as? SuccessResult)?.image?.toImageBitmap() ?: return@produceState
        Thumbs.put(key, image)
        value = key to image
    }
    // Only this link's picture: after a change of link the last one would otherwise stay for a frame.
    return key?.let(Thumbs::get) ?: loaded?.takeIf { it.first == key }?.second
}

/**
 * The thumbnails drawn lately, by link and size, about 32 MB of them: a row that scrolls back in
 * or a tab that comes back has its picture at once instead of a black box, and the bitmap isn't
 * converted again.
 */
private object Thumbs {
    private const val BUDGET = 32L shl 20
    private val images = LinkedHashMap<String, ImageBitmap>(64, 0.75f, true)
    private var bytes = 0L

    private fun weight(image: ImageBitmap) = image.width.toLong() * image.height * 4

    @Synchronized
    fun get(key: String): ImageBitmap? = images[key]

    @Synchronized
    fun put(key: String, image: ImageBitmap) {
        images.put(key, image)?.let { bytes -= weight(it) }
        bytes += weight(image)
        // The least recently used go first; the one just put stays even when it alone is too big.
        while (bytes > BUDGET && images.size > 1) {
            val eldest = images.entries.iterator().next()
            bytes -= weight(eldest.value)
            images.remove(eldest.key)
        }
    }
}

/** Keeps the pixels in memory the app can read: no hardware bitmaps on Android. */
internal expect fun ImageRequest.Builder.readablePixels(): ImageRequest.Builder

internal expect fun Image.toImageBitmap(): ImageBitmap
