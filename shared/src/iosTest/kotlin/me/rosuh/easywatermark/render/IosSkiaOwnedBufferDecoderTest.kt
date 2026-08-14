@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package me.rosuh.easywatermark.render

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.ImageBitmapConfig
import androidx.compose.ui.graphics.asSkiaBitmap
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import me.rosuh.easywatermark.session.IosSourceStager
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Image as SkiaImage
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSUUID
import platform.Foundation.writeToFile
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Phase 1 gate: CGImage draws into Skia-owned buffers (allocPixels / Data.makeUninitialized).
 * Bitmap and Image entry points must agree on pixels; bitmap must be immutable for Coil.
 */
class IosSkiaOwnedBufferDecoderTest {
    private val temporaryPaths = mutableListOf<String>()

    @AfterTest
    fun cleanup() {
        temporaryPaths.forEach(IosSourceStager::deleteQuietly)
        temporaryPaths.clear()
        IosImageIOOwnershipProbe.resetForTests()
        IosCgImageTransferProbe.resetForTests()
    }

    @Test
    fun bitmapAndImagePaths_matchPixels_andBitmapIsImmutable() {
        val path = write("owned.jpg", jpegFixture())
        IosImageIOOwnershipProbe.resetForTests()

        val bitmap = IosImageIODecoder.decodeThumbnailBitmap(path, 24)
        val image = IosImageIODecoder.decodeThumbnailSkia(path, 24)
        val composeFromBitmap = IosImageIODecoder.decodeThumbnail(path, 24)

        assertTrue(bitmap.isImmutable, "Coil path requires setImmutable before publish")
        assertEquals(bitmap.width, image.width)
        assertEquals(bitmap.height, image.height)
        assertEquals(bitmap.width, composeFromBitmap.width)
        assertEquals(bitmap.height, composeFromBitmap.height)

        val fromBitmap = SkiaImage.makeFromBitmap(bitmap).toComposeImageBitmap()
        assertTrue(pixelsNearlyEqual(fromBitmap, composeFromBitmap))

        val counts = IosImageIOOwnershipProbe.snapshotForTests()
        assertEquals(counts.sourcesCreated, counts.sourcesReleased)
        assertEquals(counts.imagesCreated, counts.imagesReleased)
    }

    private fun write(suffix: String, bytes: ByteArray): String {
        val path = NSTemporaryDirectory() + "ios_owned_" + NSUUID().UUIDString() + "_" + suffix
        assertTrue(IosByteArrayInterop.toNSData(bytes).writeToFile(path, atomically = true))
        temporaryPaths += path
        return path
    }

    private fun jpegFixture(): ByteArray {
        val w = 32
        val h = 24
        val bmp = ImageBitmap(w, h, ImageBitmapConfig.Argb8888).also { bitmap ->
            CanvasDrawScope().draw(
                Density(1f),
                LayoutDirection.Ltr,
                Canvas(bitmap),
                Size(w.toFloat(), h.toFloat()),
            ) {
                drawRect(Color.Black)
                drawRect(Color.White, Offset.Zero, Size(w / 2f, h / 2f))
                drawRect(Color.Red, Offset(w / 2f, h / 2f), Size(w / 2f, h / 2f))
            }
        }
        return SkiaImage.makeFromBitmap(bmp.asSkiaBitmap())
            .encodeToData(EncodedImageFormat.JPEG)!!.bytes
    }

    private fun pixelsNearlyEqual(a: ImageBitmap, b: ImageBitmap): Boolean {
        if (a.width != b.width || a.height != b.height) return false
        val pa = a.toPixelMap()
        val pb = b.toPixelMap()
        var checked = 0
        for (y in 0 until a.height step 2) {
            for (x in 0 until a.width step 2) {
                val ca = pa[x, y]
                val cb = pb[x, y]
                if (
                    kotlin.math.abs(ca.red - cb.red) > 0.02f ||
                    kotlin.math.abs(ca.green - cb.green) > 0.02f ||
                    kotlin.math.abs(ca.blue - cb.blue) > 0.02f
                ) {
                    return false
                }
                checked++
            }
        }
        return checked > 0
    }
}
