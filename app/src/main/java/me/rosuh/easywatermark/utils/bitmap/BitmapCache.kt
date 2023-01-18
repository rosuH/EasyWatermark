package me.rosuh.easywatermark.utils.bitmap

import android.graphics.Bitmap
import android.net.Uri
import android.util.LruCache

/**
 * Simple Bitmap cache.
 * @author hi@rosuh.me
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

    val cacheSize = maxMemory / 8

    fun getFromCache(info: BitmapInfo): BitmapValue? {
        return memoryCache.get(info)
    }

    fun addToCache(info: BitmapInfo, bitmapValue: BitmapValue?) {
        memoryCache.put(info, bitmapValue)
    }

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
