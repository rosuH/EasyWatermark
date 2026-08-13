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
        // isSampled=false: ProductThumb maxEdge is the product-final UI size, not an
        // intermediate sample. Coil AsyncImage uses Size.ORIGINAL + Precision.INEXACT;
        // sampled cache entries fail size validation and never memory-hit → blank
        // flash on LazyRow recycle (filmstrip scroll away/back).
        ImageFetchResult(
            image = skiaBitmap.asImage(),
            isSampled = false,
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
 *
 * Compose [ImageBitmap.readPixels] yields packed ARGB ints. Skia N32 on little-endian is
 * BGRA_8888 — writing RGBA into [ImageInfo.makeN32Premul] swapped R/B on filmstrip thumbs
 * (and content-theme seeds that share the Coil path). Pack BGRA for N32.
 */
private fun androidx.compose.ui.graphics.ImageBitmap.toSkiaBitmapOrNull(): Bitmap? {
    return try {
        val w = width
        val h = height
        if (w <= 0 || h <= 0) return null
        val pixels = IntArray(w * h)
        readPixels(pixels)
        val bgra = ByteArray(w * h * 4)
        var i = 0
        var o = 0
        while (i < pixels.size) {
            val c = pixels[i++]
            bgra[o++] = (c and 0xFF).toByte() // B
            bgra[o++] = ((c shr 8) and 0xFF).toByte() // G
            bgra[o++] = ((c shr 16) and 0xFF).toByte() // R
            bgra[o++] = ((c ushr 24) and 0xFF).toByte() // A
        }
        val skiaImage = SkiaImage.makeRaster(
            imageInfo = org.jetbrains.skia.ImageInfo.makeN32Premul(w, h),
            bytes = bgra,
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
