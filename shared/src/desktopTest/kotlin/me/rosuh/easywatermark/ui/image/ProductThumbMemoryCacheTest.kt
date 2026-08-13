package me.rosuh.easywatermark.ui.image

import coil3.ImageLoader
import coil3.decode.DataSource
import coil3.request.CachePolicy
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.size.Precision
import kotlinx.coroutines.runBlocking
import me.rosuh.easywatermark.data.model.MediaRef
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Surface
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Filmstrip scroll-away/back blank was caused by isSampled=true product thumbs failing Coil
 * memory-cache size validation (Size.ORIGINAL + INEXACT). Guard the hit path.
 */
class ProductThumbMemoryCacheTest {

    @Test
    fun secondExecute_sameProductThumb_hitsMemoryCache() = runBlocking {
        val png = writeSolidPng(width = 256, height = 192)
        try {
            val loader = buildProductImageLoader(coil3.PlatformContext.INSTANCE)
            val thumb = ProductThumb(
                ref = MediaRef(png.absolutePath),
                maxEdgePx = 128,
            )
            val cacheKey = productThumbCacheKey(thumb.ref.value, thumb.maxEdgePx)

            fun request() = ImageRequest.Builder(coil3.PlatformContext.INSTANCE)
                .data(thumb)
                .memoryCacheKey(cacheKey)
                .placeholderMemoryCacheKey(cacheKey)
                .memoryCachePolicy(CachePolicy.ENABLED)
                .diskCachePolicy(CachePolicy.DISABLED)
                .size(thumb.maxEdgePx)
                .precision(Precision.INEXACT)
                .build()

            val first = loader.execute(request())
            assertIs<SuccessResult>(first)
            assertTrue(
                first.dataSource == DataSource.DISK || first.dataSource == DataSource.MEMORY,
                "first load source=${first.dataSource}",
            )
            assertNotNull(loader.memoryCache)
            assertTrue(
                loader.memoryCache!!.keys.any { it.key == cacheKey },
                "memory cache should retain product thumb key",
            )

            val second = loader.execute(request())
            assertIs<SuccessResult>(second)
            // Coil 3 uses MEMORY_CACHE (not MEMORY) for strong memory hits.
            assertEquals(
                DataSource.MEMORY_CACHE,
                second.dataSource,
                "second load must hit MEMORY_CACHE (sampled thumbs used to always miss)",
            )
        } finally {
            png.delete()
        }
    }

    private fun writeSolidPng(width: Int, height: Int): File {
        val surface = Surface.makeRasterN32Premul(width, height)
        surface.canvas.clear(0xFFFF8800.toInt())
        val image = surface.makeImageSnapshot()
        val data = requireNotNull(image.encodeToData(EncodedImageFormat.PNG))
        val file = File.createTempFile("ewm-product-thumb-", ".png")
        file.writeBytes(data.bytes)
        image.close()
        surface.close()
        return file
    }
}
