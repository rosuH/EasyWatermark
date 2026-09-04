package me.rosuh.easywatermark.ui.image

import coil3.request.CachePolicy
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.size.Precision
import kotlinx.coroutines.runBlocking
import me.rosuh.easywatermark.data.model.MediaRef
import me.rosuh.easywatermark.render.DesktopExifTestFixture
import me.rosuh.easywatermark.render.DesktopExifTestFixture.BaseHeight
import me.rosuh.easywatermark.render.DesktopExifTestFixture.BaseWidth
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * Product Coil path must stay EXIF-upright **without** a second bake.
 * skiko already applies orientation; re-applying would restore stored 24×16 on ori-6.
 */
class DesktopProductThumbExifTest {

    @Test
    fun orientation6_matchesSkiaBake_notDoubleRotated() = runBlocking {
        val file = File.createTempFile("ewm-product-ori6-", ".jpg")
        file.writeBytes(DesktopExifTestFixture.jpegWithOrientation(6))
        try {
            val ctx = coil3.PlatformContext.INSTANCE
            val result = buildProductImageLoader(ctx).execute(
                ImageRequest.Builder(ctx)
                    .data(ProductThumb(ref = MediaRef(file.absolutePath), maxEdgePx = 128))
                    .memoryCachePolicy(CachePolicy.DISABLED)
                    .diskCachePolicy(CachePolicy.DISABLED)
                    .size(128)
                    .precision(Precision.INEXACT)
                    .build(),
            )
            assertIs<SuccessResult>(result)
            // Stored 24×16 + ori 6 → Skia bakes to 16×24. Double-rotate would be 24×16 again.
            assertEquals(BaseHeight, result.image.width)
            assertEquals(BaseWidth, result.image.height)
        } finally {
            file.delete()
        }
    }
}
