package me.rosuh.easywatermark.render

import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.ImageBitmapConfig
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import me.rosuh.easywatermark.data.model.ImageFormat
import me.rosuh.easywatermark.data.model.WatermarkTileMode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * S4d-127: structural proof of the Desktop format-aware encode primitive
 * ([DesktopWatermarkTextRenderer.encode]) — NOT a byte-exact JPEG golden (JPEG is lossy and the bytes
 * depend on the JDK ImageIO JPEG encoder version, so a byte-golden would be brittle). Asserts: JPEG magic,
 * re-decode dimensions, not-PNG, quality monotonicity (with slack), and the **alpha flatten** (a
 * transparent input must NOT encode to black). PNG remains byte-identical to `encodePng`.
 */
class DesktopJpegEncodeTest {

    private fun bitmap(w: Int, h: Int, draw: DrawScope.() -> Unit): ImageBitmap {
        val bmp = ImageBitmap(w, h, ImageBitmapConfig.Argb8888)
        CanvasDrawScope().draw(Density(1f), LayoutDirection.Ltr, Canvas(bmp), Size(w.toFloat(), h.toFloat())) {
            draw()
        }
        return bmp
    }

    private val JPEG_SOI = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte())
    private fun startsWithJpeg(b: ByteArray) =
        b.size >= 3 && b[0] == JPEG_SOI[0] && b[1] == JPEG_SOI[1] && b[2] == JPEG_SOI[2]
    private fun isPng(b: ByteArray) = b.size >= 1 && b[0] == 0x89.toByte()

    @Test
    fun jpeg_has_magic_redecodes_to_dims_and_is_not_png() {
        val src = bitmap(64, 48) { drawRect(Color(0xFF3366CC)) }
        val jpeg = DesktopWatermarkTextRenderer.encode(src, ImageFormat.JPEG, quality = 80)
        assertTrue(startsWithJpeg(jpeg), "encoded bytes must start with the JPEG SOI magic FF D8 FF")
        assertFalse(isPng(jpeg), "JPEG output must not be a PNG")
        val decoded = DesktopImageDecoder.decode(jpeg)
        assertEquals(64, decoded.width, "JPEG must re-decode to the source width")
        assertEquals(48, decoded.height, "JPEG must re-decode to the source height")
    }

    @Test
    fun png_branch_is_byte_identical_to_encodePng() {
        val src = bitmap(40, 30) { drawRect(Color(0xFF112233)) }
        assertTrue(
            DesktopWatermarkTextRenderer.encode(src, ImageFormat.PNG).contentEquals(
                DesktopWatermarkTextRenderer.encodePng(src),
            ),
            "encode(PNG) must be byte-identical to encodePng (golden-safe)",
        )
    }

    @Test
    fun lower_quality_jpeg_is_not_larger() {
        // A detailed background gives meaningful, comparable JPEG sizes.
        val src = DesktopWatermarkComposer.sampleBackground(256, 256)
        val low = DesktopWatermarkTextRenderer.encode(src, ImageFormat.JPEG, quality = 20)
        val high = DesktopWatermarkTextRenderer.encode(src, ImageFormat.JPEG, quality = 95)
        assertTrue(
            low.size <= high.size,
            "lower-quality JPEG must not be larger than higher-quality (low=${low.size} high=${high.size})",
        )
    }

    @Test
    fun jpeg_flattens_alpha_to_white_not_black() {
        // Fully transparent input (alpha 0 everywhere). A naive ARGB→JPEG encode renders this BLACK;
        // the flatten fills opaque white first, so it must re-decode bright, not near-black.
        val transparent = bitmap(32, 32) { /* draw nothing → alpha 0 */ }
        val jpeg = DesktopWatermarkTextRenderer.encode(transparent, ImageFormat.JPEG, quality = 90)
        assertTrue(startsWithJpeg(jpeg), "valid JPEG for a transparent input")
        val px = DesktopImageDecoder.decode(jpeg).toPixelMap()
        val c = px[px.width / 2, px.height / 2]
        assertTrue(
            c.red > 0.5f && c.green > 0.5f && c.blue > 0.5f,
            "transparent input must flatten to white (not black): r=${c.red} g=${c.green} b=${c.blue}",
        )
    }

    @Test
    fun composer_emits_png_default_and_jpeg_on_request() {
        val fixture = DesktopWatermarkComposer.sampleBackgroundPng(width = 128, height = 96)
        val asPng = DesktopWatermarkComposer.composeOverRealImage(fixture, "X", WatermarkTileMode.REPEAT)
        val asJpeg = DesktopWatermarkComposer.composeOverRealImage(
            fixture, "X", WatermarkTileMode.REPEAT, format = ImageFormat.JPEG, quality = 80,
        )
        assertTrue(isPng(asPng.png), "default composeOverRealImage output must remain PNG")
        assertTrue(startsWithJpeg(asJpeg.png), "composeOverRealImage(format=JPEG) must emit JPEG bytes")
        assertEquals(asPng.width, asJpeg.width, "format must not change composed width")
        assertEquals(asPng.height, asJpeg.height, "format must not change composed height")
    }
}
