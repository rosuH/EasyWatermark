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
 * S4d-122: structural/perceptual proof (like the other desktop render gates — NOT byte-exact host pixels)
 * that the Desktop text renderer + real-image composer honor the persisted text fields:
 *  - `textColor`    → the Compose fill colour (red ink vs blue ink) — **raster-honored**,
 *  - `textTypeface` → `fontWeight`/`fontStyle` (Bold/Italic change the raster vs Normal) — **raster-honored**
 *    (typeface affects MEASUREMENT, which `composeTextCell` bakes into the layout),
 *  - `textStyle`    → text `drawStyle` (Fill/Stroke) — **mapping wired, but currently INERT at the raster**.
 *
 * Discovered limitation (S4d-122): commonMain `WatermarkCellComposer.composeTextCell` paints with
 * `multiParagraph.paint(canvas, content.color)` — it passes only the colour, so the measured
 * `TextStyle.drawStyle` is dropped (the override path defaults to Fill). So `TextPaintStyle.Stroke` is
 * threaded through identically to the accepted iOS pattern (S4d-113) but does NOT change the raster yet;
 * raster-honoring needs a commonMain change to thread `drawStyle` into that paint call (out of scope /
 * forbidden here). This is the same reason iOS S4d-113's stroke test only asserted non-blank. The style
 * test below pins the current reality (both render; not asserted to differ) and documents the follow-up.
 *
 * Bold/Italic are **synthetic** Skiko honoring on the regular-only bundled Noto faces (ADR-0010) —
 * perceptual, not byte-parity with Android. Reuses the bundled desktopMain font (no new asset/dependency).
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
        val pa = a.toPixelMap(); val pb = b.toPixelMap()
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

    /**
     * The `TextPaintStyle` mapping is **wired** (Fill→Fill, Stroke→Stroke()) and both render without
     * crashing — but it is currently INERT at the raster: commonMain `composeTextCell` paints with
     * `multiParagraph.paint(canvas, color)`, dropping the measured `drawStyle`, so Stroke renders the same
     * as Fill (verified: `rastersDiffer(fill, stroke) == false` today). This test pins that reality; when a
     * future commonMain slice threads `drawStyle` into the paint call, Stroke will differ and this test
     * should be upgraded to assert `rastersDiffer`. NOT asserting a difference here would be dishonest only
     * if we claimed style is raster-honored — we explicitly do not (see the class KDoc).
     */
    @Test
    fun text_style_mapping_is_wired_both_render() {
        val fill = cell(style = TextPaintStyle.Fill)
        val stroke = cell(style = TextPaintStyle.Stroke)
        assertTrue(nonBlank(fill) > 0, "Fill must render visible ink")
        assertTrue(nonBlank(stroke) > 0, "Stroke must render visible ink (mapping wired, no crash)")
        // Documents the current INERT behavior (drawStyle dropped by composeTextCell). Flip to
        // assertTrue(rastersDiffer(...)) once a commonMain slice threads drawStyle into the paint call.
        assertTrue(!rastersDiffer(fill, stroke), "S4d-122: Stroke is currently inert vs Fill (commonMain drops drawStyle)")
    }

    @Test
    fun composer_threads_color_through_real_image() {
        val fixture = DesktopWatermarkComposer.sampleBackgroundPng(width = 320, height = 240)
        val red = DesktopWatermarkComposer.composeOverRealImage(
            imageBytes = fixture, text = "请勿转载 X", tileMode = WatermarkTileMode.REPEAT,
            colorArgb = 0xFFFF0000.toInt(),
        )
        val white = DesktopWatermarkComposer.composeOverRealImage(
            imageBytes = fixture, text = "请勿转载 X", tileMode = WatermarkTileMode.REPEAT,
            colorArgb = 0xFFFFFFFF.toInt(),
        )
        assertEquals(red.width, white.width, "color must not change output width")
        assertEquals(red.height, white.height, "color must not change output height")
        assertTrue(!red.png.contentEquals(white.png), "different colorArgb must change the composed PNG")
    }
}
