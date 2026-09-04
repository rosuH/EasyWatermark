package me.rosuh.easywatermark.ui

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class GalleryDragAutoScrollTest {

    private val viewport = 1200f
    private val edge = 84f

    @Test
    fun pointerInMiddle_doesNotScroll() {
        assertEquals(0f, GalleryDragAutoScroll.speedFraction(600f, viewport, edge))
        assertEquals(0f, GalleryDragAutoScroll.speedFraction(edge, viewport, edge))
        assertEquals(0f, GalleryDragAutoScroll.speedFraction(viewport - edge, viewport, edge))
    }

    @Test
    fun pointerInBottomBand_scrollsDownFasterNearerTheEdge() {
        val shallow = GalleryDragAutoScroll.speedFraction(viewport - edge + 4f, viewport, edge)
        val deep = GalleryDragAutoScroll.speedFraction(viewport - 4f, viewport, edge)
        assertTrue(shallow > 0f, "entering the band must move the grid, was $shallow")
        assertTrue(deep > shallow, "deeper in the band must be faster: $deep vs $shallow")
        assertEquals(1f, GalleryDragAutoScroll.speedFraction(viewport, viewport, edge))
    }

    @Test
    fun pointerInTopBand_scrollsUp() {
        assertTrue(GalleryDragAutoScroll.speedFraction(10f, viewport, edge) < 0f)
        assertEquals(-1f, GalleryDragAutoScroll.speedFraction(0f, viewport, edge))
    }

    @Test
    fun pointerDraggedOutsideViewport_clampsToFullSpeed() {
        assertEquals(1f, GalleryDragAutoScroll.speedFraction(viewport + 500f, viewport, edge))
        assertEquals(-1f, GalleryDragAutoScroll.speedFraction(-500f, viewport, edge))
    }

    @Test
    fun degenerateViewport_neverScrolls() {
        assertEquals(0f, GalleryDragAutoScroll.speedFraction(10f, 0f, edge))
        assertEquals(0f, GalleryDragAutoScroll.speedFraction(10f, viewport, 0f))
    }

    @Test
    fun speedFractionIsSymmetricAroundTheViewport() {
        val top = GalleryDragAutoScroll.speedFraction(20f, viewport, edge)
        val bottom = GalleryDragAutoScroll.speedFraction(viewport - 20f, viewport, edge)
        assertEquals(abs(top), abs(bottom), absoluteTolerance = 1e-5f)
    }

    @Test
    fun rangeDelta_growingForward_onlySelectsTheNewTail() {
        assertEquals(
            listOf(6 to true, 7 to true, 8 to true),
            collectDelta(anchor = 5, previous = 5, next = 8),
        )
    }

    @Test
    fun rangeDelta_shrinking_onlyDeselectsWhatLeftTheRange() {
        assertEquals(
            listOf(7 to false, 8 to false),
            collectDelta(anchor = 5, previous = 8, next = 6),
        )
    }

    @Test
    fun rangeDelta_flippingPastTheAnchor_keepsTheAnchorSelected() {
        val delta = collectDelta(anchor = 5, previous = 8, next = 2)
        assertEquals(
            listOf(6 to false, 7 to false, 8 to false, 2 to true, 3 to true, 4 to true),
            delta,
        )
        assertTrue(delta.none { it.first == 5 }, "anchor must never be touched")
    }

    @Test
    fun rangeDelta_unchangedExtent_emitsNothing() {
        assertEquals(emptyList(), collectDelta(anchor = 5, previous = 7, next = 7))
    }

    private fun collectDelta(anchor: Int, previous: Int, next: Int): List<Pair<Int, Boolean>> {
        val changes = mutableListOf<Pair<Int, Boolean>>()
        forEachDragRangeDelta(anchor, previous, next) { index, selected ->
            changes += index to selected
        }
        return changes
    }
}
