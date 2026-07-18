package me.rosuh.easywatermark.render

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toPixelMap
import me.rosuh.easywatermark.data.model.WatermarkTileMode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * The **Desktop real-image decode gate** — proves [DesktopImageDecoder] actually decodes an * encoded image through AWT `ImageIO` and that the decoded `ImageBitmap` flows through the accepted
 * commonMain composition pipeline ([WatermarkCellComposer.composeOverBackground]) to a valid watermarked
 * PNG.
 *
 * The fixture is generated deterministically (no checked-in binary asset): a sample background is
 * AWT-encoded to PNG bytes, then **decoded back through `ImageIO`** — a genuine round-trip through the
 * platform decode path. Assertions are perceptual/structural (NOT byte-exact host pixels): decode dims
 * match the source, the watermarked output is sized to the decoded image and differs from it (ink added),
 * the run is deterministic, and PNG output is valid. Desktop-only; Android decode stays native.
 */
class DesktopRealImageDecodeGoldenTest {

    private val w = 256
    private val h = 192
    private val text = "请勿转载\nDO NOT"

    private fun fixturePng() = DesktopWatermarkComposer.sampleBackgroundPng(w, h)

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
        val result = DesktopWatermarkComposer.composeOverRealImage(fixture, text, WatermarkTileMode.REPEAT)
        // Output dims equal the decoded source dims.
        assertEquals(decoded.width, result.width, "watermarked output width must equal decoded image width")
        assertEquals(decoded.height, result.height, "watermarked output height must equal decoded image height")
        // The watermarked PNG must differ from the source fixture PNG (ink was added).
        assertTrue(
            !result.png.contentEquals(fixture),
            "watermarked output must differ from the un-watermarked source fixture",
        )
        // And concretely: re-decode both and count changed pixels (real pixel evidence, not byte size).
        val before = decoded.toPixelMap()
        val after = DesktopImageDecoder.decode(result.png).toPixelMap()
        var changed = 0
        for (y in 0 until before.height) for (x in 0 until before.width) if (before[x, y] != after[x, y]) changed++
        assertTrue(changed > 0, "watermark must change pixels vs the decoded background (changed=$changed)")
    }

    @Test
    fun real_image_pipeline_is_deterministic() {
        val fixture = fixturePng()
        val a = DesktopWatermarkComposer.composeOverRealImage(fixture, text, WatermarkTileMode.REPEAT)
        val b = DesktopWatermarkComposer.composeOverRealImage(fixture, text, WatermarkTileMode.REPEAT)
        assertEquals(a.width, b.width); assertEquals(a.height, b.height)
        assertTrue(a.png.contentEquals(b.png), "real-image watermark pipeline must be deterministic (identical PNG)")
    }

    @Test
    fun output_is_valid_png() {
        val result = DesktopWatermarkComposer.composeOverRealImage(fixturePng(), text, WatermarkTileMode.CLAMP)
        assertTrue(result.png.size > 8, "PNG output must be non-trivial")
        val magic = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)
        for (i in magic.indices) assertEquals(magic[i], result.png[i], "PNG magic byte $i mismatch")
    }

    @Test
    fun decoder_rejects_undecodable_bytes() {
        // ImageIO.read returns null for non-image bytes → decoder must throw, not NPE silently.
        assertFailsWith<IllegalStateException> {
            DesktopImageDecoder.decode(byteArrayOf(1, 2, 3, 4, 5))
        }
    }
}
