package me.rosuh.easywatermark.render

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.graphics.toPixelMap
import java.awt.Color
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import org.jetbrains.skia.Image as SkiaImage

/**
 * S4d-23: the **iOS-decode proxy gate**. [IosImageDecoder] cannot be RUN here (no iOS runtime), but it
 * decodes via `org.jetbrains.skia.Image.makeFromEncoded(bytes).toComposeImageBitmap()`. Desktop skiko
 * bundles the **same Skia** behind the **same** `org.jetbrains.skia` API, so exercising that exact
 * sequence on the JVM is a faithful proxy for the iOS decode path.
 *
 * **Finding (S4d-23):** unlike AWT `ImageIO` (Desktop production decode) and Android `BitmapFactory` —
 * which return the JPEG's STORED pixels and need EXIF orientation baked in manually — **skiko's
 * `makeFromEncoded` ALREADY applies the EXIF Orientation tag**: an orientation-6 (90° CW) JPEG decodes to
 * an UPRIGHT 16×24 bitmap with the bright block top-right. This is why [IosImageDecoder] deliberately does
 * NOT apply a further rotation (that would double-rotate). If a future skiko stopped baking orientation,
 * the first test below fails loudly, signalling that iOS would then need an explicit transform.
 *
 * Fixture: deterministic generated JPEG (bright TOP-LEFT quadrant) + a spliced EXIF APP1 (no binary asset).
 */
class SkiaExifDecodeProbeTest {

    private val baseW = 24
    private val baseH = 16

    private fun baseJpeg(): ByteArray {
        val img = BufferedImage(baseW, baseH, BufferedImage.TYPE_INT_RGB)
        val g = img.createGraphics()
        g.color = Color.BLACK; g.fillRect(0, 0, baseW, baseH)
        g.color = Color.WHITE; g.fillRect(0, 0, baseW / 2, baseH / 2)
        g.dispose()
        val out = ByteArrayOutputStream(); ImageIO.write(img, "jpg", out); return out.toByteArray()
    }

    /** Splice a big-endian EXIF APP1 with Orientation=[o] right after SOI. */
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

    /** The exact iOS decode core (replicated on desktop Skia): skia decode → compose bitmap. */
    private fun skiaDecode(bytes: ByteArray): ImageBitmap =
        SkiaImage.makeFromEncoded(bytes).toComposeImageBitmap()

    @Test
    fun skia_makeFromEncoded_bakes_exif_orientation() {
        // Orientation 6 (90° CW): skiko bakes it → UPRIGHT, dims swapped to 16×24, bright block top-right.
        val decoded = skiaDecode(jpegWithOrientation(6))
        assertEquals(baseH, decoded.width, "skiko bakes orientation 6 → width swaps to $baseH")
        assertEquals(baseW, decoded.height, "skiko bakes orientation 6 → height swaps to $baseW")
        assertEquals(Quad.TR, brightestQuadrant(decoded), "orientation 6 (90° CW) places the block top-right")
    }

    @Test
    fun skia_decode_without_exif_is_unrotated() {
        // Plain JPEG (no EXIF): stays 24×16, block top-left.
        val decoded = skiaDecode(baseJpeg())
        assertEquals(baseW, decoded.width); assertEquals(baseH, decoded.height)
        assertEquals(Quad.TL, brightestQuadrant(decoded))
    }

    @Test
    fun skia_decode_orientation_8_bakes_to_bottom_left_swapped() {
        // Orientation 8 (270° CW): upright 16×24, bright block bottom-left.
        val decoded = skiaDecode(jpegWithOrientation(8))
        assertEquals(baseH, decoded.width); assertEquals(baseW, decoded.height)
        assertEquals(Quad.BL, brightestQuadrant(decoded), "orientation 8 (270° CW) places the block bottom-left")
    }
}
