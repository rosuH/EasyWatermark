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
import me.rosuh.easywatermark.data.model.UserPreferences
import me.rosuh.easywatermark.data.model.WaterMark
import me.rosuh.easywatermark.data.model.WatermarkMode
import me.rosuh.easywatermark.data.model.WatermarkTileMode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Structural proof of Desktop ICON composition via [DesktopWatermarkComposer.composeRealImage]
 * (C2 common pipeline). Not a byte-exact golden.
 */
class DesktopIconCompositionTest {

    private val PNG_MAGIC = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47)
    private fun isPng(b: ByteArray) = b.size >= 4 &&
        b[0] == PNG_MAGIC[0] && b[1] == PNG_MAGIC[1] && b[2] == PNG_MAGIC[2] && b[3] == PNG_MAGIC[3]
    private fun isJpeg(b: ByteArray) =
        b.size >= 3 && b[0] == 0xFF.toByte() && b[1] == 0xD8.toByte() && b[2] == 0xFF.toByte()

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

    private fun iconRequest(tile: WatermarkTileMode, prefs: UserPreferences) = DesktopRenderRequest(
        WaterMark.default.copy(
            markMode = WatermarkMode.Image,
            tileMode = tile,
            textSize = 14f,
            alpha = 255,
        ),
        prefs,
        0.5f,
        0.5f,
    )

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
        val out = DesktopWatermarkComposer.composeRealImage(
            bg,
            iconRequest(WatermarkTileMode.REPEAT, UserPreferences(ImageFormat.PNG, 100)),
            iconBytes = redIconPng(),
        )
        assertTrue(isPng(out.png), "PNG request must emit PNG")
        val bgDims = DesktopImageDecoder.decode(bg)
        assertEquals(bgDims.width, out.width, "composed width must match the decoded background width")
        assertEquals(bgDims.height, out.height, "composed height must match the decoded background height")
    }

    @Test
    fun jpeg_output_has_magic_and_is_not_png() {
        val bg = DesktopWatermarkComposer.sampleBackgroundPng(width = 160, height = 120)
        val out = DesktopWatermarkComposer.composeRealImage(
            bg,
            iconRequest(WatermarkTileMode.REPEAT, UserPreferences(ImageFormat.JPEG, 85)),
            iconBytes = redIconPng(),
        )
        assertTrue(isJpeg(out.png), "format=JPEG must emit JPEG bytes (FF D8 FF)")
        assertFalse(isPng(out.png), "JPEG output must not be PNG")
    }

    @Test
    fun repeat_and_clamp_both_compose_and_keep_dims() {
        val bg = DesktopWatermarkComposer.sampleBackgroundPng(width = 200, height = 150)
        val prefs = UserPreferences(ImageFormat.PNG, 100)
        val icon = redIconPng()
        val repeat = DesktopWatermarkComposer.composeRealImage(
            bg, iconRequest(WatermarkTileMode.REPEAT, prefs), iconBytes = icon,
        )
        val clamp = DesktopWatermarkComposer.composeRealImage(
            bg, iconRequest(WatermarkTileMode.CLAMP, prefs), iconBytes = icon,
        )
        assertTrue(isPng(repeat.png) && isPng(clamp.png), "both tile modes must produce valid PNG")
        assertEquals(repeat.width, clamp.width, "tile mode must not change composed width")
        assertEquals(repeat.height, clamp.height, "tile mode must not change composed height")
    }

    @Test
    fun icon_visibly_alters_the_composed_result() {
        val bg = DesktopWatermarkComposer.sampleBackgroundPng(width = 200, height = 150)
        assertEquals(0, redPixelCount(bg), "generated sample background must contain no red")
        val out = DesktopWatermarkComposer.composeRealImage(
            bg,
            iconRequest(WatermarkTileMode.REPEAT, UserPreferences(ImageFormat.PNG, 100)),
            iconBytes = redIconPng(),
        )
        assertTrue(
            redPixelCount(out.png) > 0,
            "the icon must visibly appear in the composed result (red pixels present)",
        )
    }
}
