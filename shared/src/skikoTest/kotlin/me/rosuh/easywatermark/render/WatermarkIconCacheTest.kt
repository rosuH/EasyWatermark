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
}
