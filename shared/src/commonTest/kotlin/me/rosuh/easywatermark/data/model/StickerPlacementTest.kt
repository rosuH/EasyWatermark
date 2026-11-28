package me.rosuh.easywatermark.data.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class StickerPlacementTest {
    @Test
    fun coerceKeepsOffsetsInsideSafeBand() {
        val (x, y) = StickerPlacement.coerce(-1f, 2f)
        assertEquals(StickerPlacement.SAFE_MIN, x)
        assertEquals(StickerPlacement.SAFE_MAX, y)
    }

    @Test
    fun defaultSingleIsCenteredInsideSafeBand() {
        val (x, y) = StickerPlacement.DefaultSingle
        assertTrue(x in StickerPlacement.SAFE_MIN..StickerPlacement.SAFE_MAX)
        assertTrue(y in StickerPlacement.SAFE_MIN..StickerPlacement.SAFE_MAX)
        assertEquals(0.5f, x)
        assertEquals(0.5f, y)
    }

    @Test
    fun sanitizeLiveSingleOffsetPreservesValidOffsets_andCoercesOutOfBounds() {
        assertNull(StickerPlacement.sanitizeLiveSingleOffset(0.5f, 0.5f))
        assertNull(StickerPlacement.sanitizeLiveSingleOffset(0.1f, 0.9f))
        assertNull(StickerPlacement.sanitizeLiveSingleOffset(-0.85f, -0.85f))
        assertNull(StickerPlacement.sanitizeLiveSingleOffset(-2.5f, 0.95f))
        assertEquals(
            StickerPlacement.SAFE_MIN to StickerPlacement.SAFE_MAX,
            StickerPlacement.sanitizeLiveSingleOffset(-6f, 2.5f),
        )
        assertEquals(
            0.5f to 0.5f,
            StickerPlacement.sanitizeLiveSingleOffset(Float.NaN, 0.5f),
        )
    }
}
