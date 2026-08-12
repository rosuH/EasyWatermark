package me.rosuh.easywatermark.ui.image

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import coil3.BitmapImage
import coil3.Image
import coil3.toBitmap
import org.jetbrains.skia.Image as SkiaImage

internal actual fun Image.toComposeImageBitmap(): ImageBitmap {
    val skiaBitmap = when (this) {
        is BitmapImage -> bitmap
        else -> toBitmap()
    }
    val skiaImage = SkiaImage.makeFromBitmap(skiaBitmap)
    return try {
        skiaImage.toComposeImageBitmap()
    } finally {
        skiaImage.close()
    }
}
