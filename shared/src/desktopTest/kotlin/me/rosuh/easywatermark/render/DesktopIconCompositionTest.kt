package me.rosuh.easywatermark.render

import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.ImageBitmapConfig
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
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
 * Structural proof of the Desktop ICON composition primitive * ([DesktopWatermarkComposer.composeIconOverRealImage]) — the desktopMain mirror of iOS
 * `IosWatermarkRenderer.composeIconOverImage`. **NOT a byte-exact golden** (rotated non-uniform icon
 * rasters are Skia-version-sensitive and perceptual, not Android byte parity — ). Asserts: PNG/JPEG
 * output magic, output dims == decoded background dims, REPEAT and CLAMP both compose, and that the icon
 * **visibly alters** the result (a distinctly-coloured icon — absent from the generated background —
 * appears in the composed pixels, so the assertion fails if the icon path were skipped).
 */
class DesktopIconCompositionTest {

    private val PNG_MAGIC = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47)
    private fun isPng(b: ByteArray) = b.size >= 4 &&
        b[0] == PNG_MAGIC[0] && b[1] == PNG_MAGIC[1] && b[2] == PNG_MAGIC[2] && b[3] == PNG_MAGIC[3]
    private fun isJpeg(b: ByteArray) =
        b.size >= 3 && b[0] == 0xFF.toByte() && b[1] == 0xD8.toByte() && b[2] == 0xFF.toByte()

    /**
 * A solid, fully-opaque **red** icon, PNG-encoded. The generated sample background
 * ([DesktopWatermarkComposer.sampleBackground]) contains NO red, so any red pixel in the composed
 * Output can only come from the icon — i.e. the icon path actually ran.     */
    private fun redIconPng(size: Int = 24): ByteArray {
        val bmp = ImageBitmap(size, size, ImageBitmapConfig.Argb8888)
        CanvasDrawScope().draw(
            density = Density(1f),
            layoutDirection = LayoutDirection.Ltr,
            canvas = Canvas(bmp),
            size = Size(size.toFloat(), size.toFloat()),
        ) {
            drawRect(color = Color.Red)
        }
        return DesktopWatermarkTextRenderer.encodePng(bmp)
    }

    /** Count clearly red-dominant pixels (the icon colour) in encoded [png] — tolerant of edge AA. */
    private fun redPixelCount(png: ByteArray): Int {
        val px = DesktopImageDecoder.decode(png).toPixelMap()
        var n = 0
        for (y in 0 until px.height) {
            for (x in 0 until px.width) {
                val c = px[x, y]
                if (c.red > 0.5f && c.green < 0.3f && c.blue < 0.3f) n++
            }
        }
        return n
    }

    @Test
    fun png_output_has_magic_and_matches_background_dims() {
        val bg = DesktopWatermarkComposer.sampleBackgroundPng(width = 200, height = 150)
        val out = DesktopWatermarkComposer.composeIconOverRealImage(
            bg, redIconPng(), tileMode = WatermarkTileMode.REPEAT,
        )
        assertTrue(isPng(out.png), "default composeIconOverRealImage output must be PNG")
        val bgDims = DesktopImageDecoder.decode(bg)
        assertEquals(bgDims.width, out.width, "composed width must match the decoded background width")
        assertEquals(bgDims.height, out.height, "composed height must match the decoded background height")
    }

    @Test
    fun jpeg_output_has_magic_and_is_not_png() {
        val bg = DesktopWatermarkComposer.sampleBackgroundPng(width = 160, height = 120)
        val out = DesktopWatermarkComposer.composeIconOverRealImage(
            bg, redIconPng(), tileMode = WatermarkTileMode.REPEAT, format = ImageFormat.JPEG, quality = 85,
        )
        assertTrue(isJpeg(out.png), "format=JPEG must emit JPEG bytes (FF D8 FF)")
        assertFalse(isPng(out.png), "JPEG output must not be PNG")
    }

    @Test
    fun repeat_and_clamp_both_compose_and_keep_dims() {
        val bg = DesktopWatermarkComposer.sampleBackgroundPng(width = 200, height = 150)
        val repeat = DesktopWatermarkComposer.composeIconOverRealImage(
            bg, redIconPng(), tileMode = WatermarkTileMode.REPEAT,
        )
        val clamp = DesktopWatermarkComposer.composeIconOverRealImage(
            bg, redIconPng(), tileMode = WatermarkTileMode.CLAMP,
        )
        assertTrue(isPng(repeat.png) && isPng(clamp.png), "both tile modes must produce valid PNG")
        assertEquals(repeat.width, clamp.width, "tile mode must not change composed width")
        assertEquals(repeat.height, clamp.height, "tile mode must not change composed height")
    }

    @Test
    fun icon_visibly_alters_the_composed_result() {
        val bg = DesktopWatermarkComposer.sampleBackgroundPng(width = 200, height = 150)
        // Sanity: the generated background itself has NO red.
        assertEquals(0, redPixelCount(bg), "generated sample background must contain no red")
        // REPEAT tiles the red icon across the image → red pixels appear ONLY if the icon path ran.
        // (A no-op / text-only / dropped-icon implementation would leave the result red-free and FAIL.)
        val out = DesktopWatermarkComposer.composeIconOverRealImage(
            bg, redIconPng(), tileMode = WatermarkTileMode.REPEAT,
        )
        assertTrue(
            redPixelCount(out.png) > 0,
            "the icon must visibly appear in the composed result (red pixels present)",
        )
    }
}
