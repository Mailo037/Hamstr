package app.hamstr.ui

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import coil3.Image
import coil3.request.ImageRequest
import coil3.request.allowHardware
import coil3.toBitmap

internal actual fun ImageRequest.Builder.readablePixels() = allowHardware(false)

internal actual fun Image.toImageBitmap(): ImageBitmap = toBitmap().asImageBitmap()
