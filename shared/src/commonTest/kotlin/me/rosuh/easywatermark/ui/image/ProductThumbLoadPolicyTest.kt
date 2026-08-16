package me.rosuh.easywatermark.ui.image

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ProductThumbLoadPolicyTest {

    @Test
    fun retries_firstTwoErrors_thenStops() {
        assertTrue(ProductThumbLoadPolicy.shouldRetry(0))
        assertTrue(ProductThumbLoadPolicy.shouldRetry(1))
        assertFalse(ProductThumbLoadPolicy.shouldRetry(2))
        assertFalse(ProductThumbLoadPolicy.shouldRetry(3))
    }
}
