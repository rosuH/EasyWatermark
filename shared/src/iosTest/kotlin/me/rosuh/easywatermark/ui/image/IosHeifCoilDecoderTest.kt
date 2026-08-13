@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package me.rosuh.easywatermark.ui.image

import coil3.decode.DataSource
import coil3.request.CachePolicy
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.size.Precision
import kotlinx.coroutines.test.runTest
import me.rosuh.easywatermark.data.model.MediaRef
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Production [buildProductImageLoader] must decode HEIC via [IosHeifImageDecoder]
 * (ImageIO thumbnail), not Skia `makeFromEncoded`.
 */
class IosHeifCoilDecoderTest {

    @Test
    fun productLoader_heicThumb_succeedsAndSecondHitIsMemory() = runTest {
        val pngPath = IosProductThumbAbFixtures.writeBusyPng(640, 480)
        val heic = IosProductThumbAbFixtures.encodeHeicFromPath(pngPath)
        try {
            assertTrue(!heic.isNullOrBlank(), "HEIC fixture required on this runtime")
            val ctx = coil3.PlatformContext.INSTANCE
            val loader = buildProductImageLoader(ctx)
            val thumb = ProductThumb(MediaRef(heic!!), maxEdgePx = 128)
            val key = productThumbCacheKey(thumb.ref.value, thumb.maxEdgePx)
            fun req() = ImageRequest.Builder(ctx)
                .data(thumb)
                .memoryCacheKey(key)
                .placeholderMemoryCacheKey(key)
                .memoryCachePolicy(CachePolicy.ENABLED)
                .diskCachePolicy(CachePolicy.DISABLED)
                .size(128)
                .precision(Precision.INEXACT)
                .build()
            me.rosuh.easywatermark.render.IosImageIOOwnershipProbe.resetForTests()
            val first = loader.execute(req())
            assertIs<SuccessResult>(first)
            assertTrue(first.image.width > 0 && first.image.height > 0)
            val opens = me.rosuh.easywatermark.render.IosImageIOOwnershipProbe
                .snapshotForTests().sourcesCreated
            assertEquals(
                1,
                opens,
                "HEIF Coil decode must open CGImageSource once (got $opens)",
            )
            val second = loader.execute(req())
            assertIs<SuccessResult>(second)
            assertEquals(DataSource.MEMORY_CACHE, second.dataSource)
        } finally {
            platform.Foundation.NSFileManager.defaultManager.removeItemAtPath(pngPath, null)
            heic?.let { platform.Foundation.NSFileManager.defaultManager.removeItemAtPath(it, null) }
        }
    }
}
