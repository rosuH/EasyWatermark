package me.rosuh.easywatermark.render

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Pins the watermark cell geometry formulas (CMP plan C2 portable core). These run on every
 * `:shared` target and become part of the regression net the golden harness (C1.7) extends.
 */
class WatermarkGeometryTest {

    @Test
    fun gap_zero_is_adjacent_and_100_doubles() {
        assertEquals(100, WatermarkGeometry.horizontalGap(100, 0))
        assertEquals(150, WatermarkGeometry.horizontalGap(100, 50))
        assertEquals(200, WatermarkGeometry.horizontalGap(100, 100))
        assertEquals(200, WatermarkGeometry.verticalGap(100, 100))
    }

    @Test
    fun diagonal_of_3_4_is_5() {
        assertEquals(5, WatermarkGeometry.diagonal(3f, 4f))
    }

    @Test
    fun rotated_cell_at_0_degrees_is_content_size() {
        assertEquals(100f, WatermarkGeometry.rotatedCellWidth(100f, 50f, 0f), 0.001f)
        assertEquals(50f, WatermarkGeometry.rotatedCellHeight(100f, 50f, 0f), 0.001f)
    }

    @Test
    fun rotated_cell_at_90_degrees_swaps_w_and_h() {
        // |180 − 90| path is NOT taken (90 is in 0..90); cos(90°)=0, sin(90°)=1 → axes swap.
        assertEquals(50f, WatermarkGeometry.rotatedCellWidth(100f, 50f, 90f), 0.001f)
        assertEquals(100f, WatermarkGeometry.rotatedCellHeight(100f, 50f, 90f), 0.001f)
    }

    @Test
    fun degree_normalization_branches() {
        // 0..90 → d ; 180 → |180−180|=0 ; 270 → |180−270|=90 ; 360 → 360−360=0
        assertEquals(0.0, WatermarkGeometry.normalizedRadians(0f), 1e-9)
        assertEquals(0.0, WatermarkGeometry.normalizedRadians(180f), 1e-9)
        assertEquals(PI_OVER_2, WatermarkGeometry.normalizedRadians(270f), 1e-9)
        assertEquals(0.0, WatermarkGeometry.normalizedRadians(360f), 1e-9)
        assertTrue(WatermarkGeometry.normalizedRadians(45f) > 0.0)
    }

    private companion object {
        const val PI_OVER_2 = kotlin.math.PI / 2.0
    }
}
