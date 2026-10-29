package app.hamstr.ui

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asComposeImageBitmap
import coil3.Image
import coil3.request.ImageRequest
import coil3.toBitmap

internal actual fun ImageRequest.Builder.readablePixels() = this

internal actual fun Image.toImageBitmap(): ImageBitmap = toBitmap().asComposeImageBitmap()
