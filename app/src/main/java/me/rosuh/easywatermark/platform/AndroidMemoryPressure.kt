package me.rosuh.easywatermark.platform

import android.content.ComponentCallbacks2
import android.content.Context
import coil3.SingletonImageLoader
import coil3.memory.MemoryCache
import me.rosuh.easywatermark.render.AndroidPreviewWorkingSet
import me.rosuh.easywatermark.utils.bitmap.BitmapCache
import me.rosuh.easywatermark.utils.bitmap.getAvailableMemory

/**
 * Maps [https://developer.android.com/topic/performance/memory] trim / pressure guidance
 * onto the three reconstructable Android image caches:
 * [BitmapCache] (sampled decode), Coil product thumbs (ADR-0028), preview working set (ADR-0030).
 *
 * Never [android.graphics.Bitmap.recycle]s a cache-owned bitmap.
 */
object AndroidMemoryPressure {
    /**
     * Evict reconstructable caches before a full-res export decode when the **system** is low
     * ([android.app.ActivityManager.MemoryInfo.lowMemory]) or the Java heap has less than this
     * much remaining. Two ARGB_8888 export frames at 12 MP are ~96 MiB; 64 MiB is the "tight
     * but not yet OOM" tripwire, not a guarantee.
     */
    const val HEAP_HEADROOM_BYTES: Long = 64L * 1024L * 1024L

    fun applyTrim(context: Context, level: Int): String {
        val bitmap = BitmapCache.trimForMemoryLevel(level)
        val coil = trimProductThumbMemoryCache(context, level)
        val preview = AndroidPreviewWorkingSet.onTrimMemory(level)
        return "bitmap=$bitmap coil=$coil preview=$preview"
    }

    fun applyLowMemory(context: Context): String {
        releaseReconstructableCaches(context)
        return "bitmap=evict_all coil=evict_all preview=evict_all"
    }

    /**
     * Article: query [android.app.ActivityManager.getMemoryInfo] before memory-intensive work.
     * We never skip export — we only drop reconstructable caches so the decode has heap.
     *
     * @return true if caches were released.
     */
    fun releaseReconstructableIfNeeded(context: Context): Boolean {
        val info = getAvailableMemory(context)
        val remaining = remainingHeapBytes()
        if (!isUnderMemoryPressure(info.lowMemory, remaining)) {
            return false
        }
        releaseReconstructableCaches(context)
        AndroidMemoryDiagnostics.logPressureRelease(remaining, info.lowMemory)
        return true
    }

    fun releaseReconstructableCaches(context: Context) {
        BitmapCache.evictAll()
        trimProductThumbMemoryCache(context, ComponentCallbacks2.TRIM_MEMORY_BACKGROUND)
        AndroidPreviewWorkingSet.onTrimMemory(ComponentCallbacks2.TRIM_MEMORY_BACKGROUND)
    }

    internal fun isUnderMemoryPressure(
        lowMemory: Boolean,
        remainingHeapBytes: Long,
        headroomBytes: Long = HEAP_HEADROOM_BYTES,
    ): Boolean = lowMemory || remainingHeapBytes < headroomBytes

    internal fun remainingHeapBytes(): Long {
        val runtime = Runtime.getRuntime()
        return runtime.maxMemory() - (runtime.totalMemory() - runtime.freeMemory())
    }

    /**
     * Coil 3 has no `trimToSize`. UI_HIDDEN removes entries until ~25% of [MemoryCache.maxSize];
     * BACKGROUND+ [MemoryCache.clear]s. Disk cache is off (ADR-0028) so this costs a re-decode
     * on return — cheaper than holding 30% of the heap while cached.
     */
    internal fun trimProductThumbMemoryCache(context: Context, level: Int): String {
        if (level < ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN) return "none"
        val cache = runCatching { SingletonImageLoader.get(context).memoryCache }.getOrNull()
            ?: return "none"
        return if (level >= ComponentCallbacks2.TRIM_MEMORY_BACKGROUND) {
            cache.clear()
            "evict_all"
        } else {
            softTrimMemoryCache(cache)
            "soft_25"
        }
    }

    private fun softTrimMemoryCache(cache: MemoryCache) {
        val target = (cache.maxSize / 4).coerceAtLeast(1)
        val keys = cache.keys.toList()
        var i = 0
        while (cache.size > target && i < keys.size) {
            cache.remove(keys[i])
            i++
        }
    }
}
