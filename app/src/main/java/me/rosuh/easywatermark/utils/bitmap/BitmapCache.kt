package me.rosuh.easywatermark.utils.bitmap

import android.content.ComponentCallbacks2
import android.graphics.Bitmap
import android.net.Uri
import android.util.LruCache

/**
 * Process-local bitmap cache (heap/8).
 *
 * Trim drops **references only** — never [Bitmap.recycle]. Callers / GC own pixel lifetime.
 * Android 17 memory-limiter discipline: background must not keep a full decode cache warm.
 */
object BitmapCache {
    private val memoryCache: LruCache<BitmapInfo, BitmapValue> by lazy {
        object : LruCache<BitmapInfo, BitmapValue>(cacheSize) {
            override fun sizeOf(key: BitmapInfo?, value: BitmapValue?): Int {
                return if (value?.bitmap == null) {
                    super.sizeOf(key, value)
                } else {
                    value.bitmap.allocationByteCount / 1024
                }
            }
        }
    }

    private val maxMemory by lazy { (Runtime.getRuntime().maxMemory() / 1024).toInt() }

    val cacheSize: Int
        get() = maxMemory / 8

    fun getFromCache(info: BitmapInfo): BitmapValue? {
        return memoryCache.get(info)
    }

    fun addToCache(info: BitmapInfo, bitmapValue: BitmapValue?) {
        if (bitmapValue?.bitmap == null) {
            return
        }
        memoryCache.put(info, bitmapValue)
    }

    /** Drop every entry (references only). */
    fun evictAll() {
        memoryCache.evictAll()
    }

    /**
     * Map [ComponentCallbacks2] trim levels to cache pressure.
     *
     * Post API 34 guidance still cares about:
     * - [ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN] — soft trim (~25% of max)
     * - [ComponentCallbacks2.TRIM_MEMORY_BACKGROUND] and worse — full clear
     *
     * Levels between UI_HIDDEN and BACKGROUND get the soft trim; anything at/above
     * BACKGROUND (including COMPLETE) gets [evictAll]. Unknown/lower levels no-op.
     *
     * @return descriptive action taken (`none` / `soft_25` / `evict_all`) for debug logs.
     */
    fun trimForMemoryLevel(level: Int): String {
        return when {
            level >= ComponentCallbacks2.TRIM_MEMORY_BACKGROUND -> {
                evictAll()
                "evict_all"
            }
            level >= ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN -> {
                // Soft trim to ~25% of maxSize (min 1 entry-slot of size units).
                val target = (memoryCache.maxSize() / 4).coerceAtLeast(1)
                memoryCache.trimToSize(target)
                "soft_25"
            }
            else -> "none"
        }
    }

    /** Test/debug: current size in KB units used by [LruCache.sizeOf]. */
    internal fun currentSizeKb(): Int = memoryCache.size()

    /** Test/debug: max size in KB units. */
    internal fun maxSizeKb(): Int = memoryCache.maxSize()

    data class BitmapInfo(
        val uri: Uri,
        val reqWidth: Int,
        val reqHeight: Int
    )

    data class BitmapValue(
        val bitmap: Bitmap,
        val inSample: Int,
    )
}
