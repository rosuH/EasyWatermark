package me.rosuh.easywatermark.render

import kotlin.test.Test
import kotlin.test.assertEquals

class PreviewNeighborPrefetchTest {

    @Test
    fun focusInMiddle_returnsPlusMinusTwo() {
        assertEquals(listOf(0, 1, 3, 4), neighborIndices(focus = 2, size = 5, radius = 2))
    }

    @Test
    fun focusAtStart_clipsLowIndices() {
        assertEquals(listOf(1, 2), neighborIndices(focus = 0, size = 5, radius = 2))
    }

    @Test
    fun focusOutOfRange_isEmpty() {
        assertEquals(emptyList(), neighborIndices(focus = -1, size = 5))
        assertEquals(emptyList(), neighborIndices(focus = 5, size = 5))
        assertEquals(emptyList(), neighborIndices(focus = 0, size = 0))
    }
}
