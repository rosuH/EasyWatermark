package me.rosuh.easywatermark.render

import me.rosuh.easywatermark.data.model.ImageFormat
import me.rosuh.easywatermark.data.model.UserPreferences
import me.rosuh.easywatermark.data.model.WaterMark
import me.rosuh.easywatermark.data.model.WatermarkTileMode
import me.rosuh.easywatermark.render.DesktopExifTestFixture.BaseHeight
import me.rosuh.easywatermark.render.DesktopExifTestFixture.BaseWidth
import me.rosuh.easywatermark.render.DesktopExifTestFixture.Quad
import me.rosuh.easywatermark.render.DesktopExifTestFixture.brightestQuadrant
import me.rosuh.easywatermark.render.DesktopExifTestFixture.jpegWithHugeIfdOffset
import me.rosuh.easywatermark.render.DesktopExifTestFixture.jpegWithOrientation
import me.rosuh.easywatermark.render.DesktopExifTestFixture.plainJpegWithoutExif
import me.rosuh.easywatermark.render.DesktopExifTestFixture.plainPngWithoutExif
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The **Desktop EXIF-orientation gate** — proves [DesktopImageDecoder] applies JPEG EXIF orientation
 * (1/3/6/8 + the mirror cases) at the decode edge, so the composition pipeline receives an upright
 * `ImageBitmap` with orientation-correct dimensions.
 *
 * Fixtures are **generated deterministically, no binary asset** via [DesktopExifTestFixture].
 */
class DesktopExifOrientationTest {

    @Test
    fun parses_orientation_tag_values() {
        for (o in 1..8) {
            assertEquals(o, DesktopImageDecoder.parseExifOrientation(jpegWithOrientation(o)), "orientation $o must parse")
        }
    }

    @Test
    fun non_jpeg_and_missing_exif_default_to_orientation_1() {
        assertEquals(1, DesktopImageDecoder.parseExifOrientation(plainPngWithoutExif()))
        assertEquals(1, DesktopImageDecoder.parseExifOrientation(plainJpegWithoutExif()))
        assertEquals(1, DesktopImageDecoder.parseExifOrientation(byteArrayOf(1, 2, 3, 4, 5)))
    }

    @Test
    fun malformed_exif_ifd_offset_defaults_to_orientation_1_and_decode_survives() {
        val bytes = jpegWithHugeIfdOffset()
        assertEquals(1, DesktopImageDecoder.parseExifOrientation(bytes))
        val decoded = DesktopImageDecoder.decode(bytes)
        assertEquals(BaseWidth, decoded.width)
        assertEquals(BaseHeight, decoded.height)
    }

    @Test
    fun orientation_1_normal_keeps_dims_and_top_left() {
        val b = DesktopImageDecoder.decode(jpegWithOrientation(1))
        assertEquals(BaseWidth, b.width); assertEquals(BaseHeight, b.height)
        assertEquals(Quad.TL, brightestQuadrant(b), "orientation 1 keeps the bright block top-left")
    }

    @Test
    fun orientation_3_rotates_180_to_bottom_right() {
        val b = DesktopImageDecoder.decode(jpegWithOrientation(3))
        assertEquals(BaseWidth, b.width); assertEquals(BaseHeight, b.height)
        assertEquals(Quad.BR, brightestQuadrant(b), "orientation 3 (180°) moves the block to bottom-right")
    }

    @Test
    fun orientation_2_mirrors_horizontally_to_top_right() {
        val b = DesktopImageDecoder.decode(jpegWithOrientation(2))
        assertEquals(BaseWidth, b.width); assertEquals(BaseHeight, b.height)
        assertEquals(Quad.TR, brightestQuadrant(b), "orientation 2 (mirror-H) moves the block to top-right")
    }

    @Test
    fun orientation_4_mirrors_vertically_to_bottom_left() {
        val b = DesktopImageDecoder.decode(jpegWithOrientation(4))
        assertEquals(BaseWidth, b.width); assertEquals(BaseHeight, b.height)
        assertEquals(Quad.BL, brightestQuadrant(b), "orientation 4 (mirror-V) moves the block to bottom-left")
    }

    @Test
    fun orientation_5_transposes_and_swaps_dims() {
        val b = DesktopImageDecoder.decode(jpegWithOrientation(5))
        assertEquals(BaseHeight, b.width); assertEquals(BaseWidth, b.height)
        assertEquals(Quad.TL, brightestQuadrant(b), "orientation 5 (transpose) keeps the block top-left")
    }

    @Test
    fun orientation_6_rotates_90cw_swaps_dims_to_top_right() {
        val b = DesktopImageDecoder.decode(jpegWithOrientation(6))
        assertEquals(BaseHeight, b.width); assertEquals(BaseWidth, b.height)
        assertEquals(Quad.TR, brightestQuadrant(b), "orientation 6 (90° CW) moves the block to top-right")
    }

    @Test
    fun orientation_7_transverses_to_bottom_right_and_swaps_dims() {
        val b = DesktopImageDecoder.decode(jpegWithOrientation(7))
        assertEquals(BaseHeight, b.width); assertEquals(BaseWidth, b.height)
        assertEquals(Quad.BR, brightestQuadrant(b), "orientation 7 (transverse) moves the block to bottom-right")
    }

    @Test
    fun orientation_8_rotates_270cw_swaps_dims_to_bottom_left() {
        val b = DesktopImageDecoder.decode(jpegWithOrientation(8))
        assertEquals(BaseHeight, b.width); assertEquals(BaseWidth, b.height)
        assertEquals(Quad.BL, brightestQuadrant(b), "orientation 8 (270° CW) moves the block to bottom-left")
    }

    @Test
    fun oriented_image_flows_through_composition_with_swapped_dims() {
        val result = DesktopWatermarkComposer.composeRealImage(
            imageBytes = jpegWithOrientation(6),
            request = DesktopRenderRequest(
                WaterMark.default.copy(
                    text = "请勿转载",
                    tileMode = WatermarkTileMode.REPEAT,
                ),
                UserPreferences(ImageFormat.PNG, 100),
                0.5f,
                0.5f,
            ),
        )
        assertEquals(BaseHeight, result.width, "composed width must reflect EXIF-oriented decode (swapped)")
        assertEquals(BaseWidth, result.height, "composed height must reflect EXIF-oriented decode (swapped)")
        assertTrue(result.png.size > 8, "composed PNG must be valid")
    }
}
