package me.rosuh.easywatermark.utils.ktx

import android.graphics.Bitmap
import me.rosuh.easywatermark.data.model.ImageFormat

/**
 * Maps the platform-neutral [ImageFormat] to the Android encoder type at the encode edge
 * (CMP plan D7). In the KMP phase this becomes the androidMain actual of the codec capability.
 */
fun ImageFormat.toCompressFormat(): Bitmap.CompressFormat = when (this) {
    ImageFormat.JPEG -> Bitmap.CompressFormat.JPEG
    ImageFormat.PNG -> Bitmap.CompressFormat.PNG
}

/**
 * Canonical MediaStore [android.provider.MediaStore.MediaColumns.MIME_TYPE] for Q+ export.
 * JPEG is always `image/jpeg` (never historical extension-derived `image/jpg`).
 */
fun ImageFormat.toMediaStoreMimeType(): String = when (this) {
    ImageFormat.JPEG -> "image/jpeg"
    ImageFormat.PNG -> "image/png"
}
