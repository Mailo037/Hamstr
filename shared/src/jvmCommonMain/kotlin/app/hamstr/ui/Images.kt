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

/** Loads a thumbnail as a bitmap whose pixels can be read (the ASCII renderer samples them). */
@Composable
fun rememberImage(url: String?, sizePx: Int = 480): ImageBitmap? {
    val context = LocalPlatformContext.current
    val image by produceState<ImageBitmap?>(null, url, sizePx) {
        value = null
        if (url == null) return@produceState
        val request = ImageRequest.Builder(context)
            .data(url)
            .size(sizePx)
            .readablePixels()
            .build()
        value = (SingletonImageLoader.get(context).execute(request) as? SuccessResult)?.image?.toImageBitmap()
    }
    return image
}

/** Keeps the pixels in memory the app can read: no hardware bitmaps on Android. */
internal expect fun ImageRequest.Builder.readablePixels(): ImageRequest.Builder

internal expect fun Image.toImageBitmap(): ImageBitmap
