package me.rosuh.easywatermark.render

import me.rosuh.easywatermark.data.model.MediaRef
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame

class WatermarkIconCacheTest {

    @Test
    fun sameRefAndEdge_decodesOnce() {
        val cache = WatermarkIconCache<String>()
        val ref = MediaRef("/tmp/icon-a")
        var calls = 0
        val first = cache.decoded(ref, 256) {
            calls += 1
            "icon-a"
        }
        val second = cache.decoded(ref, 256) {
            calls += 1
            "icon-a-again"
        }
        assertEquals(1, calls)
        assertEquals(1, cache.decodeCountForTests())
        assertSame(first, second)
    }

    @Test
    fun differentRef_decodesAgain() {
        val cache = WatermarkIconCache<String>()
        cache.decoded(MediaRef("/tmp/a"), 256) { "a" }
        cache.decoded(MediaRef("/tmp/b"), 256) { "b" }
        assertEquals(2, cache.decodeCountForTests())
    }

    @Test
    fun invalidate_forcesReDecode() {
        val cache = WatermarkIconCache<String>()
        val ref = MediaRef("/tmp/a")
        cache.decoded(ref, 256) { "a" }
        cache.invalidate()
        cache.decoded(ref, 256) { "a2" }
        assertEquals(2, cache.decodeCountForTests())
    }

    @Test
    fun unreadableIcon_stillThrows() {
        val cache = WatermarkIconCache<String>()
        assertFailsWith<IllegalStateException> {
            cache.decoded(MediaRef("/tmp/missing"), 256) {
                error("missing icon")
            }
        }
        assertEquals(0, cache.decodeCountForTests())
    }

    @Test
    fun switchingBetweenRecentStickers_hitsLruWithoutRedecoding() {
        val cache = WatermarkIconCache<String>()
        val a = MediaRef("/tmp/a")
        val b = MediaRef("/tmp/b")
        val c = MediaRef("/tmp/c")

        assertEquals("a", cache.decoded(a, 256) { "a" })
        assertEquals("b", cache.decoded(b, 256) { "b" })
        assertEquals("c", cache.decoded(c, 256) { "c" })
        assertEquals(3, cache.decodeCountForTests())

        assertEquals("a", cache.decoded(a, 256) { error("a should hit LRU") })
        assertEquals("b", cache.decoded(b, 256) { error("b should hit LRU") })
        assertEquals("c", cache.decoded(c, 256) { error("c should hit LRU") })
        assertEquals(3, cache.decodeCountForTests())
    }

    @Test
    fun exceedingMaxEntries_evictsLeastRecentlyUsed() {
        val cache = WatermarkIconCache<String>(maxEntries = 3)
        val a = MediaRef("/tmp/a")
        val b = MediaRef("/tmp/b")
        val c = MediaRef("/tmp/c")
        val d = MediaRef("/tmp/d")

        cache.decoded(a, 256) { "a1" }
        cache.decoded(b, 256) { "b1" }
        cache.decoded(c, 256) { "c1" }
        // Touch `a` so `b` becomes least recently used.
        assertEquals("a1", cache.decoded(a, 256) { error("a should still be cached") })

        cache.decoded(d, 256) { "d1" }
        assertEquals(4, cache.decodeCountForTests())

        assertEquals("a1", cache.decoded(a, 256) { error("a was touched and must survive") })
        assertEquals("c1", cache.decoded(c, 256) { error("c must survive") })
        assertEquals("d1", cache.decoded(d, 256) { error("d must survive") })
        assertEquals(4, cache.decodeCountForTests())

        assertEquals("b2", cache.decoded(b, 256) { "b2" })
        assertEquals(5, cache.decodeCountForTests())
    }
}
