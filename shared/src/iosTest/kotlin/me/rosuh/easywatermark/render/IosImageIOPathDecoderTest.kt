@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class, kotlin.io.encoding.ExperimentalEncodingApi::class)

package me.rosuh.easywatermark.render

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.ImageBitmapConfig
import androidx.compose.ui.graphics.asSkiaBitmap
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import me.rosuh.easywatermark.session.IosSourceStager
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Image as SkiaImage
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSUUID
import platform.Foundation.writeToFile
import kotlin.io.encoding.Base64
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** Native URL/ImageIO proof: all EXIF orientations, bounded thumbnail pixels, and +1 release paths. */
class IosImageIOPathDecoderTest {
    private val baseW = 24
    private val baseH = 16
    private val temporaryPaths = mutableListOf<String>()

    @AfterTest
    fun cleanup() {
        temporaryPaths.forEach(IosSourceStager::deleteQuietly)
        temporaryPaths.clear()
        IosImageIOOwnershipProbe.resetForTests()
    }

    @Test
    fun jpegAndPngPathMetadataAndThumbnails_areBounded() {
        val jpeg = write("jpg", jpegWithOrientation(1))
        val png = write("png", SkiaImage.makeFromBitmap(baseBitmap().asSkiaBitmap())
            .encodeToData(EncodedImageFormat.PNG)!!.bytes)

        assertEquals(baseW, IosImageIODecoder.metadata(jpeg).width)
        assertEquals(baseH, IosImageIODecoder.metadata(png).height)
        assertTrue(maxOf(
            IosImageIODecoder.decodeThumbnail(jpeg, 12).width,
            IosImageIODecoder.decodeThumbnail(jpeg, 12).height,
        ) <= 12)
        assertTrue(maxOf(
            IosImageIODecoder.decodeThumbnail(png, 12).width,
            IosImageIODecoder.decodeThumbnail(png, 12).height,
        ) <= 12)
    }

    @Test
    fun heifPathMetadataAndThumbnail_areDecodedThroughImageIO() {
        // Tiny HEIF fixture generated once from the product icon with macOS ImageIO. Keeping it
        // inline makes this a real URL/FileRepresentation-format test rather than an SDK-capability
        // assertion or an NSData production fallback.
        val heif = write("heic", Base64.Default.decode(HEIF_FIXTURE_BASE64))
        val metadata = IosImageIODecoder.metadata(heif)
        val thumbnail = IosImageIODecoder.decodeThumbnail(heif, maxEdgePx = 20)

        assertTrue(metadata.width > 0 && metadata.height > 0)
        assertTrue(thumbnail.width > 0 && thumbnail.height > 0)
        assertTrue(maxOf(thumbnail.width, thumbnail.height) <= 20)
    }

    @Test
    fun everyExifOrientation_reportsOrientedMetadata_andBakesPixelsExactlyOnce() {
        val expectedQuadrant = listOf(Quad.TL, Quad.TR, Quad.BR, Quad.BL, Quad.TL, Quad.TR, Quad.BR, Quad.BL)
        IosImageIOOwnershipProbe.resetForTests()
        for (orientation in 1..8) {
            val path = write("o$orientation.jpg", jpegWithOrientation(orientation))
            val metadata = IosImageIODecoder.metadata(path)
            val output = IosImageIODecoder.decodeThumbnail(path, 128)
            val swapped = orientation in 5..8
            assertEquals(if (swapped) baseH else baseW, metadata.width, "metadata width o=$orientation")
            assertEquals(if (swapped) baseW else baseH, metadata.height, "metadata height o=$orientation")
            assertEquals(metadata.width, output.width, "pixel width o=$orientation")
            assertEquals(metadata.height, output.height, "pixel height o=$orientation")
            assertEquals(expectedQuadrant[orientation - 1], brightestQuadrant(output), "pixel orientation o=$orientation")
        }
        val counts = IosImageIOOwnershipProbe.snapshotForTests()
        assertEquals(counts.sourcesCreated, counts.sourcesReleased, "every CGImageSource must release once")
        assertEquals(counts.imagesCreated, counts.imagesReleased, "every CGImage must release once")
    }

    @Test
    fun conversionError_afterCGImageCreation_stillReleasesImageAndSource() {
        val path = write("error.jpg", jpegWithOrientation(1))
        IosImageIOOwnershipProbe.resetForTests()
        IosImageIOOwnershipProbe.throwAfterCreateForTests = { error("forced conversion error") }
        assertFailsWith<IllegalStateException> { IosImageIODecoder.decodeThumbnail(path, 128) }
        val counts = IosImageIOOwnershipProbe.snapshotForTests()
        assertEquals(1, counts.imagesCreated)
        assertEquals(1, counts.imagesReleased)
        assertEquals(1, counts.sourcesCreated)
        assertEquals(1, counts.sourcesReleased)
    }

    private fun write(suffix: String, bytes: ByteArray): String {
        val path = NSTemporaryDirectory() + "ios_imageio_" + NSUUID().UUIDString() + "_" + suffix
        assertTrue(IosByteArrayInterop.toNSData(bytes).writeToFile(path, atomically = true))
        temporaryPaths += path
        return path
    }

    private fun baseBitmap(): ImageBitmap = ImageBitmap(baseW, baseH, ImageBitmapConfig.Argb8888).also { bitmap ->
        CanvasDrawScope().draw(Density(1f), LayoutDirection.Ltr, Canvas(bitmap), Size(baseW.toFloat(), baseH.toFloat())) {
            drawRect(Color.Black)
            drawRect(Color.White, Offset.Zero, Size(baseW / 2f, baseH / 2f))
        }
    }

    private fun jpegWithOrientation(orientation: Int): ByteArray {
        val jpeg = SkiaImage.makeFromBitmap(baseBitmap().asSkiaBitmap())
            .encodeToData(EncodedImageFormat.JPEG)!!.bytes
        val tiff = byteArrayOf(
            0x4D, 0x4D, 0x00, 0x2A, 0x00, 0x00, 0x00, 0x08,
            0x00, 0x01, 0x01, 0x12, 0x00, 0x03, 0x00, 0x00, 0x00, 0x01,
            0x00, orientation.toByte(), 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
        )
        val payload = byteArrayOf('E'.code.toByte(), 'x'.code.toByte(), 'i'.code.toByte(), 'f'.code.toByte(), 0, 0) + tiff
        val size = payload.size + 2
        val app1 = byteArrayOf(0xFF.toByte(), 0xE1.toByte(), (size ushr 8).toByte(), size.toByte()) + payload
        return byteArrayOf(jpeg[0], jpeg[1]) + app1 + jpeg.copyOfRange(2, jpeg.size)
    }

    private enum class Quad { TL, TR, BL, BR }

    private companion object {
        const val HEIF_FIXTURE_BASE64 =
            "AAAAKGZ0eXBoZWljAAAAAG1pZjFNaUhFTWlQcm1pYWZNaUhCaGVpYwAAAoFtZXRhAAAAAAAAACFoZGxyAAAAAAAAAABwaWN0AAAAAAAAAAAAAAAAAAAAACRkaW5mAAAAHGRyZWYAAAAAAAAAAQAAAAx1cmwgAAAAAQAAAA5waXRtAAAAAAABAAAAOGlpbmYAAAAAAAIAAAAVaW5mZQIAAAAAAQAAaHZjMQAAAAAVaW5mZQIAAAEAAgAAaHZjMQAAAAAaaXJlZgAAAAAAAAAOYXV4bAACAAEAAQAAAaRpcHJwAAABe2lwY28AAAATY29scm5jbHgAAgACAAaAAAAADGNsbGkAywBAAAAAFGlzcGUAAAAAAAAAKAAAACgAAAAJaXJvdAAAAAAQcGl4aQAAAAADCAgIAAAADnBpeGkAAAAAAQgAAAA3YXV4QwAAAAB1cm46bXBlZzpoZXZjOjIwMTU6YXV4aWQ6MQAAAAAMAAAACE4BpQQAAf5AAAAAcWh2Y0MBA3AAAACwAAAAAAAe8AD8/fj4AAALA6AAAQAXQAEMAf//A3AAAAMAsAAAAwAAAwAecCShAAEAI0IBAQNwAAADALAAAAMAAAMAHqAUIEHB7G4h7kWVTcCAgYAgogABAAlEAcBhcshEU2QAAABxaHZjQwEECAAAAL/IAAAAAB7wAPz8+PgAAAsDoAABABdAAQwB//8ECAAAAwC/yAAAAwAAHhcCQKEAAQAjQgEBBAgAAAMAv8gAAAMAAB7AUIEHA8wziBe5FlU3AgICAICiAAEACUQBwGHSyERTZAAAACFpcG1hAAAAAAAAAAIAAQaBAgMFiIQAAgUDBoeJhAAAACxpbG9jAAAAAEQAAAIAAQAAAAEAAAK5AAABggACAAAAAQAABDsAAADBAAAAAW1kYXQAAAAAAAACUwAAAX4oAa+hNvga88Mf8xSrfE0hYRwozUBl+lns1KSh2xMuHTuBv9Fr73M0gIi+zRA+nkD9vuUrK1Av9WcA1bkMw8/+Sm6onxWIZ5nexWvSv++PNHJlYmCxg9/57CPSzniUC/17Q508VIGf6ntbvlAE7w//fni5OmjuNe1NFHyrUbJZmyD/CvOYklUcv6F18uFMh9LtyehBR0gJA55hC26/fKc5FFsf2ml/14mAVHmk4wdaM/dEfx6ihQtH1/chwaex/fYprm8bw/cjjr4fDqfM5e+xjgVTd4kIW75ATxGbMDHBG7aKlynpwoHPwcjqwEE8uUrTkzoEhhPyWFXppBAKDPVgM5KWgTsJcsnIh1PzRDx17S+/x5ktLnORoqj/USn6MoVs0bJWLro5el/OXHs5nmCa1m9VOee72St+nFQDJwN5iItGGesbK6Q6yNu6DC4VUzszyeCBEqAW8oDauMwu6QcFsNIPFoxQx4hEoACpc54zr9G14ohvQEEyegIV7aU/AAAAvSgBr0erH20vfxdi2zOEnL2ibLJ6748M1QWzWkbTMc0whpt9/VAzHRulFaXJ2mlaTh1XygeRd3331h7LHitZ8OnxfU7pEbWeGqDH6qEs1/2qlikNvTjf/KCe10AITwKrlMDG15OU9UMp77pQe/vnwca+HE/CdfkafWI+JGvQtarNczz0ekvlCzuJUcqcaUzlapynm9lnluBSAE9w1NMNkctGob6FdQNW/jLVcPTiPqyWZ0/w4ztjWyg7Sjie5w=="
    }

    private fun brightestQuadrant(bitmap: ImageBitmap): Quad {
        val pixels = bitmap.toPixelMap()
        val sums = DoubleArray(4)
        val counts = IntArray(4)
        val middleX = pixels.width / 2
        val middleY = pixels.height / 2
        for (y in 0 until pixels.height) for (x in 0 until pixels.width) {
            val color = pixels[x, y]
            val quadrant = (if (y < middleY) 0 else 2) + (if (x < middleX) 0 else 1)
            sums[quadrant] += color.red + color.green + color.blue
            counts[quadrant]++
        }
        return when (sums.indices.maxBy { sums[it] / counts[it].coerceAtLeast(1) }) {
            0 -> Quad.TL
            1 -> Quad.TR
            2 -> Quad.BL
            else -> Quad.BR
        }
    }
}
