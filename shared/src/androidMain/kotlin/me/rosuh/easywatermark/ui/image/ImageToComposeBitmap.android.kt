package me.rosuh.easywatermark.ui.image

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import coil3.BitmapImage
import coil3.Image
import coil3.toBitmap

internal actual fun Image.toComposeImageBitmap(): ImageBitmap {
    val androidBitmap = when (this) {
        is BitmapImage -> bitmap
        else -> toBitmap()
    }
    return androidBitmap.asImageBitmap()
}
