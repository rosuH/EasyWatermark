package me.rosuh.easywatermark.render

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toPixelMap
import me.rosuh.easywatermark.data.model.TextPaintStyle
import me.rosuh.easywatermark.data.model.TextTypeface
import me.rosuh.easywatermark.data.model.WatermarkTileMode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Structural/perceptual proof that the Desktop text renderer honors the persisted text fields: `textColor`
 * (fill colour), `textTypeface` (fontWeight/fontStyle), and `textStyle` (Fill vs Stroke draw style).
 * Bold/Italic/Stroke are synthetic Skiko honoring, perceptual not byte-parity with Android.
 */
class DesktopTextParityTest {

    private fun cell(
        color: Color = Color.White,
        typeface: TextTypeface = TextTypeface.Normal,
        style: TextPaintStyle = TextPaintStyle.Fill,
    ): ImageBitmap = DesktopWatermarkTextRenderer.renderTextCell(
        text = "Ag", textSize = 64f, color = color, typeface = typeface, textStyle = style,
    )

    private fun nonBlank(b: ImageBitmap): Int {
        val p = b.toPixelMap(); var n = 0
        for (y in 0 until p.height) for (x in 0 until p.width) if (p[x, y].alpha > 0f) n++
        return n
    }

    private fun hasPixel(b: ImageBitmap, pred: (Color) -> Boolean): Boolean {
        val p = b.toPixelMap()
        for (y in 0 until p.height) for (x in 0 until p.width) {
            val c = p[x, y]
            if (c.alpha > 0f && pred(c)) return true
        }
        return false
    }

    private fun rastersDiffer(a: ImageBitmap, b: ImageBitmap): Boolean {
        if (a.width != b.width || a.height != b.height) return true
        val pa = a.toPixelMap()
        val pb = b.toPixelMap()
        for (y in 0 until pa.height) for (x in 0 until pa.width) if (pa[x, y] != pb[x, y]) return true
        return false
    }

    @Test
    fun text_color_is_honored() {
        val red = cell(color = Color.Red)
        val blue = cell(color = Color.Blue)
        assertTrue(nonBlank(red) > 0 && nonBlank(blue) > 0, "both colored cells must have visible ink")
        assertTrue(hasPixel(red) { it.red > 0.5f && it.blue < 0.3f }, "red text must produce red ink")
        assertTrue(hasPixel(blue) { it.blue > 0.5f && it.red < 0.3f }, "blue text must produce blue ink")
    }

    @Test
    fun text_typeface_is_honored() {
        val normal = cell(typeface = TextTypeface.Normal)
        val bold = cell(typeface = TextTypeface.Bold)
        val italic = cell(typeface = TextTypeface.Italic)
        assertTrue(nonBlank(normal) > 0 && nonBlank(bold) > 0 && nonBlank(italic) > 0, "all cells must have ink")
        assertTrue(rastersDiffer(normal, bold), "Bold must change the raster vs Normal")
        assertTrue(rastersDiffer(normal, italic), "Italic must change the raster vs Normal")
    }

    /** `TextPaintStyle.Stroke` renders a hairline outline that differs from the solid Fill raster. */
    @Test
    fun text_style_is_honored() {
        val fill = cell(style = TextPaintStyle.Fill)
        val stroke = cell(style = TextPaintStyle.Stroke)
        assertTrue(nonBlank(fill) > 0, "Fill must render visible ink")
        assertTrue(nonBlank(stroke) > 0, "Stroke must render visible ink")
        assertTrue(rastersDiffer(fill, stroke), "Stroke must change the raster vs Fill")
    }

    @Test
    fun composer_threads_color_through_real_image() {
        val fixture = DesktopWatermarkComposer.sampleBackgroundPng(width = 320, height = 240)
        val prefs = me.rosuh.easywatermark.data.model.UserPreferences(
            me.rosuh.easywatermark.data.model.ImageFormat.PNG,
            100,
        )
        val red = DesktopWatermarkComposer.composeRealImage(
            fixture,
            DesktopRenderRequest(
                me.rosuh.easywatermark.data.model.WaterMark.default.copy(
                    text = "请勿转载 X",
                    tileMode = WatermarkTileMode.REPEAT,
                    textColor = 0xFFFF0000.toInt(),
                ),
                prefs,
                0.5f,
                0.5f,
            ),
        )
        val white = DesktopWatermarkComposer.composeRealImage(
            fixture,
            DesktopRenderRequest(
                me.rosuh.easywatermark.data.model.WaterMark.default.copy(
                    text = "请勿转载 X",
                    tileMode = WatermarkTileMode.REPEAT,
                    textColor = 0xFFFFFFFF.toInt(),
                ),
                prefs,
                0.5f,
                0.5f,
            ),
        )
        assertEquals(red.width, white.width, "color must not change output width")
        assertEquals(red.height, white.height, "color must not change output height")
        assertTrue(!red.png.contentEquals(white.png), "different textColor must change the composed PNG")
    }
}
