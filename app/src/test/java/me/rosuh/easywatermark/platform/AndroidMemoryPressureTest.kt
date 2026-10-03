package me.rosuh.easywatermark.platform

import android.app.Application
import android.content.ComponentCallbacks2
import android.graphics.Bitmap
import android.net.Uri
import me.rosuh.easywatermark.utils.bitmap.BitmapCache
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class AndroidMemoryPressureTest {

    private val app: Application = RuntimeEnvironment.getApplication()

    @Before
    fun clearCaches() {
        BitmapCache.evictAll()
    }

    @Test
    fun isUnderMemoryPressure_systemLow_trips() {
        assertTrue(AndroidMemoryPressure.isUnderMemoryPressure(lowMemory = true, remainingHeapBytes = Long.MAX_VALUE))
    }

    @Test
    fun isUnderMemoryPressure_tightHeap_trips() {
        assertTrue(
            AndroidMemoryPressure.isUnderMemoryPressure(
                lowMemory = false,
                remainingHeapBytes = AndroidMemoryPressure.HEAP_HEADROOM_BYTES - 1,
            ),
        )
    }

    @Test
    fun isUnderMemoryPressure_healthy_doesNotTrip() {
        assertFalse(
            AndroidMemoryPressure.isUnderMemoryPressure(
                lowMemory = false,
                remainingHeapBytes = AndroidMemoryPressure.HEAP_HEADROOM_BYTES,
            ),
        )
    }

    @Test
    fun applyTrim_uiHidden_softTrimsBitmapCache() {
        putCachedBitmap("content://pressure/soft")
        val action = AndroidMemoryPressure.applyTrim(app, ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN)
        assertTrue(action.contains("bitmap=soft_25"))
        assertTrue(action.contains("coil="))
        assertTrue(action.contains("preview="))
    }

    @Test
    fun applyTrim_background_evictsBitmapCache() {
        val key = putCachedBitmap("content://pressure/bg")
        val action = AndroidMemoryPressure.applyTrim(app, ComponentCallbacks2.TRIM_MEMORY_BACKGROUND)
        assertTrue(action, action.contains("bitmap=evict_all"))
        assertTrue(action, action.contains("coil=evict_all") || action.contains("coil=none"))
        assertNull(BitmapCache.getFromCache(key))
    }

    @Test
    fun applyLowMemory_evictsBitmapCache() {
        val key = putCachedBitmap("content://pressure/low")
        val action = AndroidMemoryPressure.applyLowMemory(app)
        assertEquals("bitmap=evict_all coil=evict_all preview=evict_all", action)
        assertNull(BitmapCache.getFromCache(key))
    }

    @Test
    fun releaseReconstructableCaches_dropsBitmapCacheWithoutRecycling() {
        val bmp = Bitmap.createBitmap(24, 24, Bitmap.Config.ARGB_8888)
        val key = BitmapCache.BitmapInfo(Uri.parse("content://pressure/release"), 24, 24)
        BitmapCache.addToCache(key, BitmapCache.BitmapValue(bmp, 1))
        AndroidMemoryPressure.releaseReconstructableCaches(app)
        assertNull(BitmapCache.getFromCache(key))
        assertTrue(!bmp.isRecycled)
    }

    @Test
    fun applyTrim_runningModerate_isNoopForBitmapCache() {
        val key = putCachedBitmap("content://pressure/mod")
        @Suppress("DEPRECATION")
        val action = AndroidMemoryPressure.applyTrim(app, ComponentCallbacks2.TRIM_MEMORY_RUNNING_MODERATE)
        assertTrue(action.contains("bitmap=none"))
        assertNotNullCached(key)
    }

    private fun putCachedBitmap(uri: String): BitmapCache.BitmapInfo {
        val bmp = Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888)
        val key = BitmapCache.BitmapInfo(Uri.parse(uri), 16, 16)
        BitmapCache.addToCache(key, BitmapCache.BitmapValue(bmp, 1))
        return key
    }

    private fun assertNotNullCached(key: BitmapCache.BitmapInfo) {
        assertTrue(BitmapCache.getFromCache(key) != null)
    }
}
