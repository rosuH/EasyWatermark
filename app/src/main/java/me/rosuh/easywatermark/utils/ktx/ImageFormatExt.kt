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
