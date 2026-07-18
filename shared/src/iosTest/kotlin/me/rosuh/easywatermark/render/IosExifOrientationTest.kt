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
import kotlin.test.Test
import kotlin.test.assertEquals
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Image as SkiaImage

/**
 * The **iOS EXIF-orientation decode proof** (`iosSimulatorArm64Test` / `iosArm64` link). Asserts * [IosImageDecoder.decode] returns an UPRIGHT image for an EXIF-tagged JPEG — which it does because
 * **Skia bakes EXIF orientation during decode** (no manual transform; see the [IosImageDecoder] KDoc and
 * the finding in `SkiaExifDecodeProbeTest`). Fixture: Skia-encode a bright-TOP-LEFT image to JPEG, splice
 * an EXIF APP1 (orientation 6), decode, and assert the result is upright with swapped dimensions.
 *
 * Proof level: **compile + native test-executable LINK** (the bar). The RUN needs an iOS runtime
 * (none installed; do not install — constraint), so it is deferred to /C5 — at which point
 * its RUN is the definitive confirmation that iOS skiko bakes orientation like the desktop skiko proxy
 * already proves at runtime (`SkiaExifDecodeProbeTest`).
 */
class IosExifOrientationTest {

    private val baseW = 24
    private val baseH = 16

    private fun baseBitmap(): ImageBitmap {
        val bmp = ImageBitmap(baseW, baseH, ImageBitmapConfig.Argb8888)
        CanvasDrawScope().draw(Density(1f), LayoutDirection.Ltr, Canvas(bmp), Size(baseW.toFloat(), baseH.toFloat())) {
            drawRect(color = Color.Black)
            drawRect(color = Color.White, topLeft = Offset.Zero, size = Size(baseW / 2f, baseH / 2f))
        }
        return bmp
    }

    private fun baseJpeg(): ByteArray {
        val data = SkiaImage.makeFromBitmap(baseBitmap().asSkiaBitmap()).encodeToData(EncodedImageFormat.JPEG)
            ?: error("Skia JPEG encode returned null")
        return data.bytes
    }

    private fun jpegWithOrientation(o: Int): ByteArray {
        val base = baseJpeg()
        val tiff = byteArrayOf(
            0x4D, 0x4D, 0x00, 0x2A, 0x00, 0x00, 0x00, 0x08,
            0x00, 0x01, 0x01, 0x12, 0x00, 0x03, 0x00, 0x00, 0x00, 0x01,
            0x00, o.toByte(), 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
        )
        val exif = byteArrayOf('E'.code.toByte(), 'x'.code.toByte(), 'i'.code.toByte(), 'f'.code.toByte(), 0, 0)
        val payload = exif + tiff
        val segLen = payload.size + 2
        val app1 = byteArrayOf(0xFF.toByte(), 0xE1.toByte(), (segLen ushr 8).toByte(), (segLen and 0xFF).toByte()) + payload
        return byteArrayOf(base[0], base[1]) + app1 + base.copyOfRange(2, base.size)
    }

    private enum class Quad { TL, TR, BL, BR }

    private fun brightestQuadrant(b: ImageBitmap): Quad {
        val px = b.toPixelMap()
        val mx = px.width / 2; val my = px.height / 2
        val sums = DoubleArray(4); val counts = IntArray(4)
        for (y in 0 until px.height) for (x in 0 until px.width) {
            val c = px[x, y]; val lum = c.red + c.green + c.blue
            val q = (if (y < my) 0 else 2) + (if (x < mx) 0 else 1)
            sums[q] += lum; counts[q]++
        }
        val means = DoubleArray(4) { if (counts[it] > 0) sums[it] / counts[it] else 0.0 }
        return when (means.indices.maxByOrNull { means[it] }!!) { 0 -> Quad.TL; 1 -> Quad.TR; 2 -> Quad.BL; else -> Quad.BR }
    }

    @Test
    fun decode_bakes_exif_orientation_6_to_upright_swapped_dims() {
        val upright = IosImageDecoder.decode(jpegWithOrientation(6))
        assertEquals(baseH, upright.width, "EXIF-oriented decode swaps width")
        assertEquals(baseW, upright.height, "EXIF-oriented decode swaps height")
        assertEquals(Quad.TR, brightestQuadrant(upright), "orientation 6 moves the block top-right")
    }

    @Test
    fun decode_without_exif_is_unrotated() {
        val out = IosImageDecoder.decode(baseJpeg())
        assertEquals(baseW, out.width); assertEquals(baseH, out.height)
        assertEquals(Quad.TL, brightestQuadrant(out))
    }
}
