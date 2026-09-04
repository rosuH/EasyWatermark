package me.rosuh.easywatermark.ui.image

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ProductThumbFitTest {

    @Test
    fun usableSquareThumb_keepsFourByThree_rejectsSlivers() {
        assertTrue(ProductThumbFit.isUsableSquareThumb(128, 128, 128))
        assertTrue(ProductThumbFit.isUsableSquareThumb(128, 96, 128))
        assertFalse(ProductThumbFit.isUsableSquareThumb(128, 95, 128))
        assertFalse(ProductThumbFit.isUsableSquareThumb(128, 72, 128))
        assertFalse(ProductThumbFit.isUsableSquareThumb(17, 128, 128))
        assertFalse(ProductThumbFit.isUsableSquareThumb(0, 128, 128))
        assertFalse(ProductThumbFit.isUsableSquareThumb(128, 128, 0))
    }

    @Test
    fun inSampleSizeForCrop_keepsShortEdge() {
        assertEquals(8, ProductThumbFit.inSampleSizeForCrop(1080, 8000, 128))
        assertEquals(16, ProductThumbFit.inSampleSizeForCrop(4000, 3000, 128))
        assertEquals(4, ProductThumbFit.inSampleSizeForCrop(3200, 800, 128))
        assertEquals(2, ProductThumbFit.inSampleSizeForCrop(480, 320, 128))
        assertEquals(1, ProductThumbFit.inSampleSizeForCrop(100, 80, 128))
        assertEquals(1, ProductThumbFit.inSampleSizeForCrop(128, 128, 128))
    }
}
