package me.rosuh.easywatermark.ui.image

import me.rosuh.easywatermark.data.model.MediaRef
import me.rosuh.easywatermark.ui.theme.ContentEditorTheme
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ProductThumbTest {
    @Test
    fun uiThumbMaxEdge_matchesThemeSeed() {
        assertEquals(ContentEditorTheme.SEED_MAX_EDGE, ProductThumb.UI_THUMB_MAX_EDGE)
        assertEquals(128, ProductThumb.UI_THUMB_MAX_EDGE)
    }

    @Test
    fun cacheKey_includesRefAndEdge() {
        val a = productThumbCacheKey("file:///a.jpg", 128)
        val b = productThumbCacheKey("file:///a.jpg", 128)
        val c = productThumbCacheKey("file:///a.jpg", 96)
        val d = productThumbCacheKey("file:///b.jpg", 128)
        assertEquals(a, b)
        assertTrue(a != c)
        assertTrue(a != d)
        assertTrue(a.contains("128"))
        assertTrue(a.contains("file:///a.jpg"))
    }

    @Test
    fun productThumb_defaults() {
        val t = ProductThumb(MediaRef("path/to.jpg"))
        assertEquals(ProductThumb.UI_THUMB_MAX_EDGE, t.maxEdgePx)
        assertEquals(ProductThumb.Purpose.Chrome, t.purpose)
    }
}
