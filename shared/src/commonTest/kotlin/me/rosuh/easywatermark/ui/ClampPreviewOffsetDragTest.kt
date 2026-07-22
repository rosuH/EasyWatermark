package me.rosuh.easywatermark.ui

import me.rosuh.easywatermark.data.model.WatermarkTileMode
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * C4.4R.1 G1–G8: pure fitted-image CLAMP drag geometry contract.
 * Host wiring and Session persistence are out of scope.
 */
class ClampPreviewOffsetDragTest {

    // G1: portrait image in landscape container → vertical bars (pillarbox).
    @Test
    fun g1_portrait_in_landscape_is_pillarboxed() {
        val fitted = computeFittedImageRect(
            containerWidth = 800f,
            containerHeight = 400f,
            imageWidth = 1000f,
            imageHeight = 2000f,
        )
        assertNotNull(fitted)
        // scale = min(0.8, 0.2) = 0.2 → 200×400, centered horizontally
        assertEquals(200f, fitted.width, 0.001f)
        assertEquals(400f, fitted.height, 0.001f)
        assertEquals(300f, fitted.left, 0.001f)
        assertEquals(0f, fitted.top, 0.001f)
        assertTrue(fitted.left > 0f, "letterbox/pillar bars on sides")
        assertEquals(0f, fitted.top, 0.001f)
    }

    // G2: landscape image in portrait container → horizontal bars (letterbox).
    @Test
    fun g2_landscape_in_portrait_is_letterboxed() {
        val fitted = computeFittedImageRect(
            containerWidth = 400f,
            containerHeight = 800f,
            imageWidth = 2000f,
            imageHeight = 1000f,
        )
        assertNotNull(fitted)
        // scale = min(0.2, 0.8) = 0.2 → 400×200, centered vertically
        assertEquals(400f, fitted.width, 0.001f)
        assertEquals(200f, fitted.height, 0.001f)
        assertEquals(0f, fitted.left, 0.001f)
        assertEquals(300f, fitted.top, 0.001f)
        assertTrue(fitted.top > 0f, "letterbox bars top/bottom")
    }

    // G3: delta uses fitted dimensions, not container size.
    @Test
    fun g3_delta_normalizes_by_fitted_not_container() {
        val fitted = computeFittedImageRect(
            containerWidth = 1000f,
            containerHeight = 500f,
            imageWidth = 1000f,
            imageHeight = 2000f,
        )!!
        // fitted 250×500 inside 1000×500 (left=375)
        assertEquals(250f, fitted.width, 0.001f)
        assertEquals(500f, fitted.height, 0.001f)

        val byFitted = applyClampDragDelta(
            startOffsetX = 0.5f,
            startOffsetY = 0.5f,
            dragDeltaX = 25f,
            dragDeltaY = -50f,
            fitted = fitted,
        )
        // 25/250=0.1, -50/500=-0.1
        assertEquals(0.6f, byFitted.first, 0.0001f)
        assertEquals(0.4f, byFitted.second, 0.0001f)

        // Same pixel delta against container would be wrong (25/1000=0.025, -50/500=-0.1)
        assertTrue(
            abs(byFitted.first - (0.5f + 25f / 1000f)) > 0.05f,
            "must not normalize by container width",
        )
    }

    // G4: representative drag from centre to upper-right near (0.83, 0.17).
    @Test
    fun g4_representative_drag_reaches_upper_right_region() {
        val fitted = computeFittedImageRect(
            containerWidth = 1000f,
            containerHeight = 500f,
            imageWidth = 1000f,
            imageHeight = 2000f,
        )!!
        // Target (0.83, 0.17) from (0.5, 0.5) → Δn=(+0.33, -0.33)
        val dx = 0.33f * fitted.width
        val dy = -0.33f * fitted.height
        val end = applyClampDragDelta(0.5f, 0.5f, dx, dy, fitted)
        assertEquals(0.83f, end.first, 0.01f)
        assertEquals(0.17f, end.second, 0.01f)
        assertTrue(end.first in 0.75f..0.90f)
        assertTrue(end.second in 0.10f..0.25f)
    }

    // G5: clamp to 0f..1f.
    @Test
    fun g5_offsets_clamp_to_unit_interval() {
        val fitted = FittedImageRect(left = 0f, top = 0f, width = 100f, height = 100f)
        val low = applyClampDragDelta(0.1f, 0.1f, -50f, -50f, fitted)
        assertEquals(0f, low.first, 0.0001f)
        assertEquals(0f, low.second, 0.0001f)
        val high = applyClampDragDelta(0.9f, 0.9f, 50f, 50f, fitted)
        assertEquals(1f, high.first, 0.0001f)
        assertEquals(1f, high.second, 0.0001f)
    }

    // G6: start in letterbox/pillarbox rejected.
    @Test
    fun g6_drag_start_outside_fitted_rejected() {
        val fitted = computeFittedImageRect(
            containerWidth = 800f,
            containerHeight = 400f,
            imageWidth = 1000f,
            imageHeight = 2000f,
        )!!
        // Pillar bar on the left: x=10 is outside fitted (left=300)
        assertFalse(isPointInsideFittedImage(10f, 200f, fitted))
        assertTrue(isPointInsideFittedImage(400f, 200f, fitted))

        val rejected = resolveClampDragCommit(
            ClampDragGestureSnapshot(
                tileMode = WatermarkTileMode.CLAMP,
                selectionIdAtStart = "a",
                selectionIdAtEnd = "a",
                startInFittedImage = false,
                startOffsetX = 0.5f,
                startOffsetY = 0.5f,
                totalDragX = 10f,
                totalDragY = -10f,
                fitted = fitted,
                cancelled = false,
            ),
        )
        assertNull(rejected)
    }

    // G7: no-commit cases.
    @Test
    fun g7_repeat_zero_cancel_invalid_dimensions_produce_no_commit() {
        val okFitted = FittedImageRect(0f, 0f, 200f, 200f)
        fun snap(
            tile: WatermarkTileMode = WatermarkTileMode.CLAMP,
            dragX: Float = 20f,
            dragY: Float = -10f,
            cancelled: Boolean = false,
            fitted: FittedImageRect? = okFitted,
            startIn: Boolean = true,
        ) = ClampDragGestureSnapshot(
            tileMode = tile,
            selectionIdAtStart = "sel",
            selectionIdAtEnd = "sel",
            startInFittedImage = startIn,
            startOffsetX = 0.5f,
            startOffsetY = 0.5f,
            totalDragX = dragX,
            totalDragY = dragY,
            fitted = fitted,
            cancelled = cancelled,
        )

        assertNull(resolveClampDragCommit(snap(tile = WatermarkTileMode.REPEAT)))
        assertNull(resolveClampDragCommit(snap(dragX = 0f, dragY = 0f)))
        assertNull(resolveClampDragCommit(snap(cancelled = true)))
        assertNull(
            resolveClampDragCommit(
                snap(fitted = null),
            ),
        )
        // zero / NaN / infinite image dims → no fitted rect
        assertNull(
            computeFittedImageRect(100f, 100f, 0f, 50f),
        )
        assertNull(
            computeFittedImageRect(100f, 100f, Float.NaN, 50f),
        )
        assertNull(
            computeFittedImageRect(100f, 100f, 50f, Float.POSITIVE_INFINITY),
        )
        assertNull(
            computeFittedImageRect(0f, 100f, 50f, 50f),
        )
        assertNull(
            resolveClampDragCommit(
                snap(
                    fitted = FittedImageRect(0f, 0f, Float.NaN, 100f),
                ),
            ),
        )
    }

    // G8: selection identity frozen at start must still match at end.
    @Test
    fun g8_selection_mismatch_at_end_produces_no_commit() {
        val fitted = FittedImageRect(0f, 0f, 200f, 200f)
        val commit = resolveClampDragCommit(
            ClampDragGestureSnapshot(
                tileMode = WatermarkTileMode.CLAMP,
                selectionIdAtStart = "uri-a",
                selectionIdAtEnd = "uri-b",
                startInFittedImage = true,
                startOffsetX = 0.5f,
                startOffsetY = 0.5f,
                totalDragX = 20f,
                totalDragY = -10f,
                fitted = fitted,
                cancelled = false,
            ),
        )
        assertNull(commit)

        val ok = resolveClampDragCommit(
            ClampDragGestureSnapshot(
                tileMode = WatermarkTileMode.CLAMP,
                selectionIdAtStart = "uri-a",
                selectionIdAtEnd = "uri-a",
                startInFittedImage = true,
                startOffsetX = 0.5f,
                startOffsetY = 0.5f,
                totalDragX = 20f,
                totalDragY = -10f,
                fitted = fitted,
                cancelled = false,
            ),
        )
        assertNotNull(ok)
        assertEquals(0.5f + 20f / 200f, ok.offsetX, 0.0001f)
        assertEquals(0.5f - 10f / 200f, ok.offsetY, 0.0001f)
    }
}
