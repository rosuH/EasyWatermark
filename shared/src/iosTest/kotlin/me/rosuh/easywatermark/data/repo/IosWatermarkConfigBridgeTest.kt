package me.rosuh.easywatermark.data.repo

import androidx.compose.ui.graphics.toPixelMap
import kotlinx.coroutines.runBlocking
import me.rosuh.easywatermark.data.datastore.createWaterMarkDataStore
import me.rosuh.easywatermark.data.model.TextPaintStyle
import me.rosuh.easywatermark.data.model.TextTypeface
import me.rosuh.easywatermark.data.model.WatermarkTileMode
import me.rosuh.easywatermark.render.IosWatermarkRenderer
import platform.Foundation.NSUUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * S4d-102: iOS runtime proof that the common [WaterMarkRepository], behind the Swift-facing
 * [IosWatermarkConfigBridge], reads/writes the watermark text (S4d-102), rotation degree (S4d-103),
 * tile mode (S4d-104), alpha (S4d-105), text color (S4d-107), text size (S4d-109), and h/v gaps
 * (S4d-110) through the iOS [createWaterMarkDataStore] (`NSDocumentDirectory`) store. RUNS on
 * `iosSimulatorArm64Test`.
 *
 * A unique store name (NSUUID) is used so the initial read is the true default and the test does not
 * collide with the app's default store or other runs (the simulator data container is ephemeral).
 */
class IosWatermarkConfigBridgeTest {

    private fun bridge(name: String) = IosWatermarkConfigBridge(
        WaterMarkRepository(
            dataStore = createWaterMarkDataStore(name = name),
            defaultTextProvider = { "EasyWatermark 水印" },
            tileModeFromStorageId = { WatermarkTileMode.fromStorageId(it) },
            logError = {},
        ),
    )

    @Test
    fun bridge_watermark_text_roundtrip() = runBlocking {
        val b = bridge("s4d102_roundtrip_" + NSUUID().UUIDString())

        // Empty store -> the injected default text.
        assertEquals("EasyWatermark 水印", b.currentText(), "default watermark text must be the constant")

        // Write through the shared editor use-case, then read back.
        b.setText("请勿转载")
        assertEquals("请勿转载", b.currentText(), "watermark text must persist after setText")

        // Overwrite again to prove repeated edits persist.
        b.setText("DO NOT REDISTRIBUTE")
        assertEquals("DO NOT REDISTRIBUTE", b.currentText(), "watermark text must persist on re-edit")
    }

    @Test
    fun bridge_watermark_degree_roundtrip() = runBlocking {
        val b = bridge("s4d103_degree_" + NSUUID().UUIDString())

        // Empty store -> WaterMark.default.degree (matches the prior Swift hardcoded 315.0).
        assertEquals(315f, b.currentDegree(), "default degree must be 315 (fresh-install default)")

        // Write through the shared editor use-case, then read back.
        b.setDegree(90f)
        assertEquals(90f, b.currentDegree(), "degree must persist after setDegree")

        // Repeated edit persists.
        b.setDegree(0f)
        assertEquals(0f, b.currentDegree(), "degree must persist on re-edit")

        // Out-of-range write is clamped by the shared WatermarkConfigRules.clampDegree (0..360).
        b.setDegree(400f)
        assertEquals(360f, b.currentDegree(), "degree must clamp to 360 (shared clamp)")
    }

    @Test
    fun bridge_watermark_tilemode_roundtrip() = runBlocking {
        val b = bridge("s4d104_tilemode_" + NSUUID().UUIDString())

        // Empty store -> WaterMark.default.tileMode (matches the prior Swift hardcoded REPEAT).
        assertEquals(WatermarkTileMode.REPEAT, b.currentTileMode(), "default tile mode must be REPEAT")

        // Write through the shared editor use-case, then read back (CLAMP = single decal).
        b.setTileMode(WatermarkTileMode.CLAMP)
        assertEquals(WatermarkTileMode.CLAMP, b.currentTileMode(), "tile mode must persist after setTileMode")

        // Switch back to prove repeated edits persist.
        b.setTileMode(WatermarkTileMode.REPEAT)
        assertEquals(WatermarkTileMode.REPEAT, b.currentTileMode(), "tile mode must persist on re-edit")
    }

    @Test
    fun bridge_watermark_alpha_roundtrip() = runBlocking {
        val b = bridge("s4d105_alpha_" + NSUUID().UUIDString())

        // Empty store -> WaterMark.default.alpha (255 = fully opaque; matches the prior Swift 1.0).
        assertEquals(255, b.currentAlphaByte(), "default alpha byte must be 255 (opaque)")

        // 50% -> byte 127 because alphaPercentToByte = (percent/100*255).toInt() truncates 127.5 -> 127.
        b.setAlphaPercent(50f)
        assertEquals(127, b.currentAlphaByte(), "50% must persist as byte 127 (truncating)")

        // Edges: 0% -> 0, 100% -> 255.
        b.setAlphaPercent(0f)
        assertEquals(0, b.currentAlphaByte(), "0% must persist as byte 0")
        b.setAlphaPercent(100f)
        assertEquals(255, b.currentAlphaByte(), "100% must persist as byte 255")
    }

    @Test
    fun bridge_watermark_textcolor_roundtrip() = runBlocking {
        val b = bridge("s4d107_color_" + NSUUID().UUIDString())

        // Empty store -> WaterMark.default.textColor (#FFB800 amber). NOTE: this is the value the iOS
        // render now uses on a fresh install, replacing the prior hardcoded white (an alignment, not a
        // default-preserving change).
        assertEquals(0xFFFFB800.toInt(), b.currentTextColor(), "default text color must be amber #FFB800")

        // Write through the shared editor use-case, then read back.
        b.setTextColor(0xFFFFFFFF.toInt())
        assertEquals(0xFFFFFFFF.toInt(), b.currentTextColor(), "text color must persist as white")

        // Second value to prove repeated edits persist.
        b.setTextColor(0xFF000000.toInt())
        assertEquals(0xFF000000.toInt(), b.currentTextColor(), "text color must persist as black on re-edit")
    }

    @Test
    fun bridge_watermark_textsize_roundtrip() = runBlocking {
        val b = bridge("s4d109_size_" + NSUUID().UUIDString())

        // Empty store -> WaterMark.default.textSize (14). NOTE: this is the value the iOS render now uses
        // on a fresh install, replacing the prior hardcoded 24 (an alignment, not default-preserving).
        assertEquals(14f, b.currentTextSize(), "default text size must be 14 (fresh-install render size)")

        // Write through the shared editor use-case, then read back.
        b.setTextSize(30f)
        assertEquals(30f, b.currentTextSize(), "text size must persist after setTextSize")

        // Clamp floor: a 0 write is stored 0 (editor coerceAtLeast(0f)) but the repo read clamps to >= 1.
        b.setTextSize(0f)
        assertEquals(1f, b.currentTextSize(), "text size read must clamp to the 1 floor (MIN_TEXT_SIZE)")
    }

    @Test
    fun bridge_watermark_gap_roundtrip() = runBlocking {
        val b = bridge("s4d110_gap_" + NSUUID().UUIDString())

        // Empty store -> WaterMark.default gaps (0/0). NOTE: this is what the iOS render now uses on a
        // fresh install, replacing the prior hardcoded 40/40 (an alignment, denser tiling).
        assertEquals(0, b.currentHGap(), "default hGap must be 0 (fresh-install render)")
        assertEquals(0, b.currentVGap(), "default vGap must be 0 (fresh-install render)")

        // Write a representative non-default value through the shared editor, then read back.
        b.setHGap(40)
        b.setVGap(40)
        assertEquals(40, b.currentHGap(), "hGap must persist as 40")
        assertEquals(40, b.currentVGap(), "vGap must persist as 40")

        // Clamp: negative -> 0, over max -> 500 (WatermarkConfigRules clamps 0..500).
        b.setHGap(-5)
        assertEquals(0, b.currentHGap(), "hGap must clamp negative to 0")
        b.setVGap(600)
        assertEquals(500, b.currentVGap(), "vGap must clamp over-max to 500")
    }

    @Test
    fun bridge_watermark_typeface_roundtrip() = runBlocking {
        val b = bridge("s4d112_typeface_" + NSUUID().UUIDString())

        // Empty store -> WaterMark.default.textTypeface (Normal; preserves the prior regular iOS output).
        assertEquals(TextTypeface.Normal, b.currentTextTypeface(), "default typeface must be Normal")

        // Each of the four values persists and reads back through the shared editor.
        for (tf in listOf(TextTypeface.Italic, TextTypeface.Bold, TextTypeface.BoldItalic, TextTypeface.Normal)) {
            b.setTextTypeface(tf)
            assertEquals(tf, b.currentTextTypeface(), "typeface must persist as $tf")
        }
    }

    /**
     * S4d-112: the iOS renderer honors all four typefaces — each renders a visible (non-blank) text cell.
     * Uses the system font (FontFamily.Default) so bold/italic are Compose **synthetic** (faux-bold/italic),
     * mirroring Android's synthesis intent; this is perceptual, not byte-parity. Cheap: one small cell each.
     */
    @Test
    fun renderer_honors_each_typeface_nonblank() {
        for (tf in listOf(TextTypeface.Normal, TextTypeface.Italic, TextTypeface.Bold, TextTypeface.BoldItalic)) {
            val cell = IosWatermarkRenderer.renderTextCell(text = "Ag", textSize = 48f, typeface = tf)
            val pixels = cell.toPixelMap()
            var visible = 0
            for (y in 0 until pixels.height) for (x in 0 until pixels.width) if (pixels[x, y].alpha > 0f) visible++
            assertTrue(visible > 0, "typeface $tf must render visible text pixels (visible=$visible)")
        }
    }

    @Test
    fun bridge_watermark_textstyle_roundtrip() = runBlocking {
        val b = bridge("s4d113_textstyle_" + NSUUID().UUIDString())

        // Empty store -> WaterMark.default.textStyle (Fill; preserves the prior filled iOS output).
        assertEquals(TextPaintStyle.Fill, b.currentTextStyle(), "default text style must be Fill")

        // Stroke persists and reads back through the shared editor.
        b.setTextStyle(TextPaintStyle.Stroke)
        assertEquals(TextPaintStyle.Stroke, b.currentTextStyle(), "text style must persist as Stroke")

        // Switch back to prove repeated edits persist.
        b.setTextStyle(TextPaintStyle.Fill)
        assertEquals(TextPaintStyle.Fill, b.currentTextStyle(), "text style must persist as Fill on re-edit")
    }

    /**
     * S4d-113: the iOS renderer honors both paint styles — each renders a visible (non-blank) text cell.
     * Stroke maps to a Compose `Stroke()` (default width 0 = Skia hairline), mirroring Android's stroked
     * text (`Paint.Style.STROKE` at the default strokeWidth 0); this is perceptual Skiko honoring, not
     * byte-parity. Cheap: one small cell each.
     */
    @Test
    fun renderer_honors_each_textstyle_nonblank() {
        for (style in listOf(TextPaintStyle.Fill, TextPaintStyle.Stroke)) {
            val cell = IosWatermarkRenderer.renderTextCell(text = "Ag", textSize = 48f, textStyle = style)
            val pixels = cell.toPixelMap()
            var visible = 0
            for (y in 0 until pixels.height) for (x in 0 until pixels.width) if (pixels[x, y].alpha > 0f) visible++
            assertTrue(visible > 0, "text style $style must render visible text pixels (visible=$visible)")
        }
    }
}
