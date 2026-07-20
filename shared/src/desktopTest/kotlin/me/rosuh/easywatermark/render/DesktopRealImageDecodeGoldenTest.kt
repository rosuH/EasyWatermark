package me.rosuh.easywatermark.render

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toPixelMap
import me.rosuh.easywatermark.data.model.ImageFormat
import me.rosuh.easywatermark.data.model.UserPreferences
import me.rosuh.easywatermark.data.model.WaterMark
import me.rosuh.easywatermark.data.model.WatermarkTileMode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * The Desktop real-image decode gate — [DesktopImageDecoder] + [DesktopWatermarkComposer.composeRealImage]
 * (C2: CommonWatermarkPipeline). Structural assertions only (not host byte-parity).
 */
class DesktopRealImageDecodeGoldenTest {

    private val w = 256
    private val h = 192
    private val text = "请勿转载\nDO NOT"
    private val prefs = UserPreferences(ImageFormat.PNG, 100)

    private fun fixturePng() = DesktopWatermarkComposer.sampleBackgroundPng(w, h)

    private fun request(tile: WatermarkTileMode) = DesktopRenderRequest(
        WaterMark.default.copy(text = text, tileMode = tile),
        prefs,
        0.5f,
        0.5f,
    )

    private fun nonBlank(b: ImageBitmap): Int {
        val p = b.toPixelMap(); var n = 0
        for (y in 0 until p.height) for (x in 0 until p.width) if (p[x, y].alpha > 0f) n++
        return n
    }

    @Test
    fun imageio_decodes_fixture_to_expected_dimensions() {
        val decoded = DesktopImageDecoder.decode(fixturePng())
        assertEquals(w, decoded.width, "ImageIO-decoded width must match the source fixture")
        assertEquals(h, decoded.height, "ImageIO-decoded height must match the source fixture")
        assertTrue(nonBlank(decoded) > 0, "decoded image must have visible pixels")
    }

    @Test
    fun watermarked_output_is_sized_to_decoded_image_and_differs_from_it() {
        val fixture = fixturePng()
        val decoded = DesktopImageDecoder.decode(fixture)
        val result = DesktopWatermarkComposer.composeRealImage(fixture, request(WatermarkTileMode.REPEAT))
        assertEquals(decoded.width, result.width, "watermarked output width must equal decoded image width")
        assertEquals(decoded.height, result.height, "watermarked output height must equal decoded image height")
        assertTrue(
            !result.png.contentEquals(fixture),
            "watermarked output must differ from the un-watermarked source fixture",
        )
        val before = decoded.toPixelMap()
        val after = DesktopImageDecoder.decode(result.png).toPixelMap()
        var changed = 0
        for (y in 0 until before.height) for (x in 0 until before.width) if (before[x, y] != after[x, y]) changed++
        assertTrue(changed > 0, "watermark must change pixels vs the decoded background (changed=$changed)")
    }

    @Test
    fun real_image_pipeline_is_deterministic() {
        val fixture = fixturePng()
        val a = DesktopWatermarkComposer.composeRealImage(fixture, request(WatermarkTileMode.REPEAT))
        val b = DesktopWatermarkComposer.composeRealImage(fixture, request(WatermarkTileMode.REPEAT))
        assertEquals(a.width, b.width); assertEquals(a.height, b.height)
        assertTrue(a.png.contentEquals(b.png), "real-image watermark pipeline must be deterministic (identical PNG)")
    }

    @Test
    fun output_is_valid_png() {
        val result = DesktopWatermarkComposer.composeRealImage(fixturePng(), request(WatermarkTileMode.CLAMP))
        assertTrue(result.png.size > 8, "PNG output must be non-trivial")
        val magic = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)
        for (i in magic.indices) assertEquals(magic[i], result.png[i], "PNG magic byte $i mismatch")
    }

    @Test
    fun decoder_rejects_undecodable_bytes() {
        assertFailsWith<IllegalStateException> {
            DesktopImageDecoder.decode(byteArrayOf(1, 2, 3, 4, 5))
        }
    }
}
