package me.rosuh.easywatermark.render

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * S4d-2 / S4d-366: verifies the commonMain offscreen cell composition primitive
 * ([WatermarkCellComposer]) — Desktop/iOS path only (`skikoTest` source set).
 *
 * Requires a real Compose ImageBitmap backend (Skiko). Not part of `commonTest` /
 * `:shared:testAndroidHostTest` (AGP Android host JVM has no Bitmap implementation).
 * Executed by `:shared:desktopTest` and `:shared:iosSimulatorArm64Test`.
 * Android production renderer stays native; this primitive is not Android-production-wired.
 */
class WatermarkCellComposerTest {

    @Test
    fun cell_dims_match_geometry() {
        val cell = WatermarkCellComposer.composeRotatedCell(100, 50, degree = 0f)
        val expectedW = WatermarkGeometry.horizontalGap(
            WatermarkGeometry.rotatedCellWidth(100f, 50f, 0f).toInt(), 0,
        )
        val expectedH = WatermarkGeometry.verticalGap(
            WatermarkGeometry.rotatedCellHeight(100f, 50f, 0f).toInt(), 0,
        )
        assertEquals(expectedW, cell.width)
        assertEquals(expectedH, cell.height)
    }

    @Test
    fun rotation_90_swaps_aabb_axes() {
        val flat = WatermarkCellComposer.composeRotatedCell(100, 50, degree = 0f)
        val rotated = WatermarkCellComposer.composeRotatedCell(100, 50, degree = 90f)
        // 90 degrees rotates the content AABB: width/height swap (cos90=0, sin90=1).
        assertEquals(flat.width, rotated.height)
        assertEquals(flat.height, rotated.width)
    }

    @Test
    fun gap_100_doubles_each_axis() {
        val base = WatermarkCellComposer.composeRotatedCell(100, 50, degree = 0f, hGapPercent = 0, vGapPercent = 0)
        val gapped = WatermarkCellComposer.composeRotatedCell(100, 50, degree = 0f, hGapPercent = 100, vGapPercent = 100)
        assertEquals(base.width * 2, gapped.width)
        assertEquals(base.height * 2, gapped.height)
    }

    @Test
    fun renders_nonblank_pixels() {
        // White content on a transparent cell: some pixels must be non-transparent after drawing.
        val cell = WatermarkCellComposer.composeRotatedCell(80, 40, degree = 0f, contentColor = Color.White)
        val pixels = cell.toPixelMap()
        var nonBlank = 0
        for (y in 0 until pixels.height) {
            for (x in 0 until pixels.width) {
                if (pixels[x, y] != Color.Transparent) nonBlank++
            }
        }
        assertTrue(nonBlank > 0, "composed cell must render visible (non-transparent) pixels")
    }
}
