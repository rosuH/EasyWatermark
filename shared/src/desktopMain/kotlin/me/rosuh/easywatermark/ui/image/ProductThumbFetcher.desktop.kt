package me.rosuh.easywatermark.ui.image

import coil3.ImageLoader
import coil3.asImage
import coil3.decode.DataSource
import coil3.fetch.FetchResult
import coil3.fetch.Fetcher
import coil3.fetch.ImageFetchResult
import coil3.request.Options
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import me.rosuh.easywatermark.render.DesktopImageDecoder
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.Image as SkiaImage
import java.io.File

/**
 * Desktop product UI Fetcher: path → [DesktopImageDecoder.decodeThumbnail] (EXIF + max-edge).
 */
class ProductThumbFetcher(
    private val data: ProductThumb,
) : Fetcher {

    override suspend fun fetch(): FetchResult? = withContext(Dispatchers.IO) {
        if (data.ref.isEmpty()) return@withContext null
        val path = data.ref.value
        val file = File(path)
        if (!file.isFile) return@withContext null
        val size = data.maxEdgePx.coerceIn(64, 512)
        val imageBitmap = runCatching {
            DesktopImageDecoder.decodeThumbnail(file, maxEdgePx = size)
        }.getOrNull() ?: return@withContext null
        // Compose Desktop ImageBitmap → Skia pixels → coil BitmapImage
        val skiaBitmap = imageBitmap.toSkiaBitmapOrNull() ?: return@withContext null
        ImageFetchResult(
            image = skiaBitmap.asImage(),
            isSampled = true,
            dataSource = DataSource.DISK,
        )
    }

    class Factory : Fetcher.Factory<ProductThumb> {
        override fun create(
            data: ProductThumb,
            options: Options,
            imageLoader: ImageLoader,
        ): Fetcher = ProductThumbFetcher(data)
    }
}

/**
 * Read Compose [androidx.compose.ui.graphics.ImageBitmap] pixels into a Skia [Bitmap]
 * for Coil's multiplatform BitmapImage path.
 */
private fun androidx.compose.ui.graphics.ImageBitmap.toSkiaBitmapOrNull(): Bitmap? {
    return try {
        val w = width
        val h = height
        if (w <= 0 || h <= 0) return null
        val pixels = IntArray(w * h)
        readPixels(pixels)
        // Compose ARGB ints → Skia N32 premul via encoded PNG round-trip is heavy;
        // use Skia Bitmap.installPixels with RGBA.
        val rgba = ByteArray(w * h * 4)
        var i = 0
        var o = 0
        while (i < pixels.size) {
            val c = pixels[i++]
            rgba[o++] = ((c shr 16) and 0xFF).toByte() // R
            rgba[o++] = ((c shr 8) and 0xFF).toByte() // G
            rgba[o++] = (c and 0xFF).toByte() // B
            rgba[o++] = ((c ushr 24) and 0xFF).toByte() // A
        }
        val skiaImage = SkiaImage.makeRaster(
            imageInfo = org.jetbrains.skia.ImageInfo.makeN32Premul(w, h),
            bytes = rgba,
            rowBytes = w * 4,
        )
        val bmp = Bitmap()
        if (!bmp.allocPixels(skiaImage.imageInfo)) {
            skiaImage.close()
            return null
        }
        skiaImage.readPixels(bmp)
        skiaImage.close()
        bmp
    } catch (_: Exception) {
        null
    }
}
