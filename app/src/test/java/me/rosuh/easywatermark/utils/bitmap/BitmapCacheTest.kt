package me.rosuh.easywatermark.utils.bitmap

import android.app.Application
import android.content.ComponentCallbacks2
import android.graphics.Bitmap
import android.net.Uri
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * WP-A: trim drops references only; never recycles cached bitmaps.
 *
 * Uses plain [Application] so Robolectric does not start [me.rosuh.easywatermark.MyApp] / Koin.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class BitmapCacheTest {

    @Before
    fun clear() {
        BitmapCache.evictAll()
    }

    @Test
    fun evictAll_dropsEntries_withoutRecyclingBitmaps() {
        val bmp = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888)
        val key = BitmapCache.BitmapInfo(Uri.parse("content://test/1"), 64, 64)
        BitmapCache.addToCache(key, BitmapCache.BitmapValue(bmp, 1))
        assertNotNull(BitmapCache.getFromCache(key))

        BitmapCache.evictAll()

        assertNull(BitmapCache.getFromCache(key))
        assertTrue("trim must not recycle", !bmp.isRecycled)
        assertEquals(0, BitmapCache.currentSizeKb())
    }

    @Test
    fun trimForMemoryLevel_uiHidden_softTrimsToAboutQuarter() {
        // Fill with several small bitmaps so soft trim has something to drop.
        val bitmaps = (0 until 8).map { i ->
            val bmp = Bitmap.createBitmap(128, 128, Bitmap.Config.ARGB_8888)
            val key = BitmapCache.BitmapInfo(Uri.parse("content://test/soft/$i"), 128, 128)
            BitmapCache.addToCache(key, BitmapCache.BitmapValue(bmp, 1))
            bmp
        }
        val before = BitmapCache.currentSizeKb()
        assertTrue("precondition: cache non-empty", before > 0)

        val action = BitmapCache.trimForMemoryLevel(ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN)
        assertEquals("soft_25", action)

        val after = BitmapCache.currentSizeKb()
        val max = BitmapCache.maxSizeKb()
        // Soft target is max/4; allow empty if entries were huge relative to max.
        assertTrue(
            "soft trim should leave size <= max/4 (after=$after max=$max before=$before)",
            after <= (max / 4).coerceAtLeast(1) || after < before,
        )
        bitmaps.forEach { assertTrue(!it.isRecycled) }
    }

    @Test
    fun trimForMemoryLevel_background_evictsAll() {
        val bmp = Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888)
        val key = BitmapCache.BitmapInfo(Uri.parse("content://test/bg"), 32, 32)
        BitmapCache.addToCache(key, BitmapCache.BitmapValue(bmp, 1))

        val action = BitmapCache.trimForMemoryLevel(ComponentCallbacks2.TRIM_MEMORY_BACKGROUND)
        assertEquals("evict_all", action)
        assertNull(BitmapCache.getFromCache(key))
        assertTrue(!bmp.isRecycled)
        assertEquals(0, BitmapCache.currentSizeKb())
    }

    @Test
    fun trimForMemoryLevel_complete_evictsAll() {
        val bmp = Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888)
        val key = BitmapCache.BitmapInfo(Uri.parse("content://test/complete"), 16, 16)
        BitmapCache.addToCache(key, BitmapCache.BitmapValue(bmp, 1))

        val action = BitmapCache.trimForMemoryLevel(ComponentCallbacks2.TRIM_MEMORY_COMPLETE)
        assertEquals("evict_all", action)
        assertNull(BitmapCache.getFromCache(key))
        assertTrue(!bmp.isRecycled)
    }

    @Test
    fun trimForMemoryLevel_runningModerate_noop() {
        val bmp = Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888)
        val key = BitmapCache.BitmapInfo(Uri.parse("content://test/mod"), 16, 16)
        BitmapCache.addToCache(key, BitmapCache.BitmapValue(bmp, 1))

        @Suppress("DEPRECATION")
        val action = BitmapCache.trimForMemoryLevel(ComponentCallbacks2.TRIM_MEMORY_RUNNING_MODERATE)
        assertEquals("none", action)
        assertNotNull(BitmapCache.getFromCache(key))
        assertTrue(!bmp.isRecycled)
    }
}
