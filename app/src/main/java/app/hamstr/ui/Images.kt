package app.hamstr.ui

import android.graphics.Bitmap
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.platform.LocalContext
import coil3.imageLoader
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.request.allowHardware
import coil3.toBitmap

/** Loads a thumbnail as a software bitmap (the ASCII renderer reads its pixels). */
@Composable
fun rememberBitmap(url: String?, sizePx: Int = 480): Bitmap? {
    val context = LocalContext.current
    val bitmap by produceState<Bitmap?>(null, url, sizePx) {
        value = null
        if (url == null) return@produceState
        val request = ImageRequest.Builder(context)
            .data(url)
            .size(sizePx)
            .allowHardware(false)
            .build()
        value = (context.imageLoader.execute(request) as? SuccessResult)?.image?.toBitmap()
    }
    return bitmap
}
