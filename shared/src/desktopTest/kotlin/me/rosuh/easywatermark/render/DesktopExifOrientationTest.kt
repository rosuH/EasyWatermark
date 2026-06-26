package me.rosuh.easywatermark.render

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toPixelMap
import me.rosuh.easywatermark.data.model.WatermarkTileMode
import java.awt.Color
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * S4d-21: the **Desktop EXIF-orientation gate** — proves [DesktopImageDecoder] applies JPEG EXIF
 * orientation (1/3/6/8 + the mirror cases) at the decode edge, so the composition pipeline receives an
 * upright `ImageBitmap` with orientation-correct dimensions.
 *
 * Fixtures are **generated deterministically, no binary asset**: a base image with a bright TOP-LEFT
 * quadrant is JPEG-encoded via `ImageIO`, then a minimal EXIF APP1 segment carrying the chosen Orientation
 * tag (0x0112) is spliced in right after SOI. Decoding that fixture must (a) parse the orientation and
 * (b) move the bright quadrant to the expected corner with the expected (possibly swapped) dimensions.
 * JPEG is lossy, so the assertion is on a large bright BLOCK's quadrant (robust), not exact pixels.
 */
class DesktopExifOrientationTest {

    private val baseW = 24
    private val baseH = 16

    /** Base image: top-left quadrant white, rest black (TYPE_INT_RGB for JPEG). */
    private fun baseImage(): BufferedImage {
        val img = BufferedImage(baseW, baseH, BufferedImage.TYPE_INT_RGB)
        val g = img.createGraphics()
        g.color = Color.BLACK
        g.fillRect(0, 0, baseW, baseH)
        g.color = Color.WHITE
        g.fillRect(0, 0, baseW / 2, baseH / 2) // top-left quadrant
        g.dispose()
        return img
    }

    private fun baseJpeg(): ByteArray {
        val out = ByteArrayOutputStream()
        ImageIO.write(baseImage(), "jpg", out)
        return out.toByteArray()
    }

    /** Splice a minimal big-endian ("MM") EXIF APP1 with Orientation=[orientation] right after JPEG SOI. */
    private fun jpegWithOrientation(orientation: Int): ByteArray {
        val base = baseJpeg()
        require(base[0].toInt() and 0xFF == 0xFF && base[1].toInt() and 0xFF == 0xD8) { "base is not a JPEG" }
        // TIFF (MM): header(8) + IFD count(2) + 1 entry(12) + next-IFD(4) = 26 bytes.
        val tiff = byteArrayOf(
            0x4D, 0x4D,                                     // "MM" big-endian
            0x00, 0x2A,                                     // TIFF magic 42
            0x00, 0x00, 0x00, 0x08,                         // IFD0 offset = 8
            0x00, 0x01,                                     // 1 directory entry
            0x01, 0x12,                                     // tag 0x0112 Orientation
            0x00, 0x03,                                     // type SHORT
            0x00, 0x00, 0x00, 0x01,                         // count 1
            0x00, orientation.toByte(), 0x00, 0x00,         // value (SHORT left-justified, big-endian)
            0x00, 0x00, 0x00, 0x00,                         // next IFD = 0
        )
        val exifHeader = byteArrayOf('E'.code.toByte(), 'x'.code.toByte(), 'i'.code.toByte(), 'f'.code.toByte(), 0, 0)
        val payload = exifHeader + tiff
        val segLen = payload.size + 2 // APP1 length field includes the 2 length bytes
        val app1 = byteArrayOf(0xFF.toByte(), 0xE1.toByte(), (segLen ushr 8).toByte(), (segLen and 0xFF).toByte()) + payload
        return byteArrayOf(base[0], base[1]) + app1 + base.copyOfRange(2, base.size)
    }

    /** A valid JPEG with EXIF whose IFD0 offset points outside the TIFF block. */
    private fun jpegWithHugeIfdOffset(): ByteArray {
        val base = baseJpeg()
        require(base[0].toInt() and 0xFF == 0xFF && base[1].toInt() and 0xFF == 0xD8) { "base is not a JPEG" }
        val tiff = byteArrayOf(
            0x4D, 0x4D,                                     // "MM" big-endian
            0x00, 0x2A,                                     // TIFF magic 42
            0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), // invalid IFD0 offset
        )
        val exifHeader = byteArrayOf('E'.code.toByte(), 'x'.code.toByte(), 'i'.code.toByte(), 'f'.code.toByte(), 0, 0)
        val payload = exifHeader + tiff
        val segLen = payload.size + 2
        val app1 = byteArrayOf(0xFF.toByte(), 0xE1.toByte(), (segLen ushr 8).toByte(), (segLen and 0xFF).toByte()) + payload
        return byteArrayOf(base[0], base[1]) + app1 + base.copyOfRange(2, base.size)
    }

    private enum class Quad { TL, TR, BL, BR }

    /** Which quadrant of [b] is brightest (mean luminance)? */
    private fun brightestQuadrant(b: ImageBitmap): Quad {
        val px = b.toPixelMap()
        val mx = px.width / 2; val my = px.height / 2
        val sums = DoubleArray(4); val counts = IntArray(4)
        for (y in 0 until px.height) for (x in 0 until px.width) {
            val c = px[x, y]
            val lum = c.red + c.green + c.blue
            val q = (if (y < my) 0 else 2) + (if (x < mx) 0 else 1)
            sums[q] += lum; counts[q]++
        }
        val means = DoubleArray(4) { if (counts[it] > 0) sums[it] / counts[it] else 0.0 }
        val idx = means.indices.maxByOrNull { means[it] }!!
        return when (idx) { 0 -> Quad.TL; 1 -> Quad.TR; 2 -> Quad.BL; else -> Quad.BR }
    }

    @Test
    fun parses_orientation_tag_values() {
        for (o in 1..8) {
            assertEquals(o, DesktopImageDecoder.parseExifOrientation(jpegWithOrientation(o)), "orientation $o must parse")
        }
    }

    @Test
    fun non_jpeg_and_missing_exif_default_to_orientation_1() {
        // PNG (no EXIF) → 1
        val pngOut = ByteArrayOutputStream(); ImageIO.write(baseImage(), "png", pngOut)
        assertEquals(1, DesktopImageDecoder.parseExifOrientation(pngOut.toByteArray()))
        // Plain JPEG without an EXIF APP1 → 1
        assertEquals(1, DesktopImageDecoder.parseExifOrientation(baseJpeg()))
        // Garbage → 1 (best-effort, never throws)
        assertEquals(1, DesktopImageDecoder.parseExifOrientation(byteArrayOf(1, 2, 3, 4, 5)))
    }

    @Test
    fun malformed_exif_ifd_offset_defaults_to_orientation_1_and_decode_survives() {
        val bytes = jpegWithHugeIfdOffset()

        assertEquals(1, DesktopImageDecoder.parseExifOrientation(bytes))

        val decoded = DesktopImageDecoder.decode(bytes)
        assertEquals(baseW, decoded.width)
        assertEquals(baseH, decoded.height)
    }

    @Test
    fun orientation_1_normal_keeps_dims_and_top_left() {
        val b = DesktopImageDecoder.decode(jpegWithOrientation(1))
        assertEquals(baseW, b.width); assertEquals(baseH, b.height)
        assertEquals(Quad.TL, brightestQuadrant(b), "orientation 1 keeps the bright block top-left")
    }

    @Test
    fun orientation_3_rotates_180_to_bottom_right() {
        val b = DesktopImageDecoder.decode(jpegWithOrientation(3))
        assertEquals(baseW, b.width); assertEquals(baseH, b.height) // 180° keeps dims
        assertEquals(Quad.BR, brightestQuadrant(b), "orientation 3 (180°) moves the block to bottom-right")
    }

    @Test
    fun orientation_2_mirrors_horizontally_to_top_right() {
        val b = DesktopImageDecoder.decode(jpegWithOrientation(2))
        assertEquals(baseW, b.width); assertEquals(baseH, b.height)
        assertEquals(Quad.TR, brightestQuadrant(b), "orientation 2 (mirror-H) moves the block to top-right")
    }

    @Test
    fun orientation_4_mirrors_vertically_to_bottom_left() {
        val b = DesktopImageDecoder.decode(jpegWithOrientation(4))
        assertEquals(baseW, b.width); assertEquals(baseH, b.height)
        assertEquals(Quad.BL, brightestQuadrant(b), "orientation 4 (mirror-V) moves the block to bottom-left")
    }

    @Test
    fun orientation_5_transposes_and_swaps_dims() {
        val b = DesktopImageDecoder.decode(jpegWithOrientation(5))
        assertEquals(baseH, b.width); assertEquals(baseW, b.height)
        assertEquals(Quad.TL, brightestQuadrant(b), "orientation 5 (transpose) keeps the block top-left")
    }

    @Test
    fun orientation_6_rotates_90cw_swaps_dims_to_top_right() {
        val b = DesktopImageDecoder.decode(jpegWithOrientation(6))
        assertEquals(baseH, b.width); assertEquals(baseW, b.height) // 90° swaps dims
        assertEquals(Quad.TR, brightestQuadrant(b), "orientation 6 (90° CW) moves the block to top-right")
    }

    @Test
    fun orientation_7_transverses_to_bottom_right_and_swaps_dims() {
        val b = DesktopImageDecoder.decode(jpegWithOrientation(7))
        assertEquals(baseH, b.width); assertEquals(baseW, b.height)
        assertEquals(Quad.BR, brightestQuadrant(b), "orientation 7 (transverse) moves the block to bottom-right")
    }

    @Test
    fun orientation_8_rotates_270cw_swaps_dims_to_bottom_left() {
        val b = DesktopImageDecoder.decode(jpegWithOrientation(8))
        assertEquals(baseH, b.width); assertEquals(baseW, b.height) // 270° swaps dims
        assertEquals(Quad.BL, brightestQuadrant(b), "orientation 8 (270° CW) moves the block to bottom-left")
    }

    @Test
    fun oriented_image_flows_through_composition_with_swapped_dims() {
        // composeOverRealImage must receive the ORIENTED image: a 90° fixture → output dims are swapped.
        val result = DesktopWatermarkComposer.composeOverRealImage(
            imageBytes = jpegWithOrientation(6), text = "请勿转载", tileMode = WatermarkTileMode.REPEAT,
        )
        assertEquals(baseH, result.width, "composed width must reflect EXIF-oriented decode (swapped)")
        assertEquals(baseW, result.height, "composed height must reflect EXIF-oriented decode (swapped)")
        assertTrue(result.png.size > 8, "composed PNG must be valid")
    }
}
