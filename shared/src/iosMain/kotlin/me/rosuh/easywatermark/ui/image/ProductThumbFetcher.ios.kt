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
import me.rosuh.easywatermark.render.IosImageIODecoder
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.Image as SkiaImage

/**
 * iOS product UI Fetcher: path → [IosImageIODecoder.decodeThumbnail] (orientation-aware, max-edge).
 */
class ProductThumbFetcher(
    private val data: ProductThumb,
) : Fetcher {

    override suspend fun fetch(): FetchResult? = withContext(Dispatchers.Default) {
        if (data.ref.isEmpty()) return@withContext null
        val path = data.ref.value
        if (path.isBlank() || path == "preview") return@withContext null
        val size = data.maxEdgePx.coerceIn(64, 512)
        val imageBitmap = runCatching {
            IosImageIODecoder.decodeThumbnail(path, maxEdgePx = size)
        }.getOrNull() ?: return@withContext null
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

private fun androidx.compose.ui.graphics.ImageBitmap.toSkiaBitmapOrNull(): Bitmap? {
    return try {
        val w = width
        val h = height
        if (w <= 0 || h <= 0) return null
        val pixels = IntArray(w * h)
        readPixels(pixels)
        val rgba = ByteArray(w * h * 4)
        var i = 0
        var o = 0
        while (i < pixels.size) {
            val c = pixels[i++]
            rgba[o++] = ((c shr 16) and 0xFF).toByte()
            rgba[o++] = ((c shr 8) and 0xFF).toByte()
            rgba[o++] = (c and 0xFF).toByte()
            rgba[o++] = ((c ushr 24) and 0xFF).toByte()
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
    } catch (_: Throwable) {
        null
    }
}
