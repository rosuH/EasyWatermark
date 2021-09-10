package me.rosuh.easywatermark.utils

import android.graphics.Bitmap
import android.net.Uri
import android.util.LruCache

/**
 * Simple Bitmap cache.
 * @author hi@rosuh.me
 */
object BitmapCache {

    private val memoryCache: LruCache<BitmapInfo, Bitmap> by lazy {
        object : LruCache<BitmapInfo, Bitmap>(cacheSize) {
            override fun sizeOf(key: BitmapInfo?, value: Bitmap?): Int {
                return if (value == null) {
                    super.sizeOf(key, value)
                } else {
                    value.byteCount / 1024
                }
            }
        }
    }

    private val maxMemory by lazy {
        (Runtime.getRuntime().maxMemory() / 1024).toInt()
    }

    val cacheSize = maxMemory / 4


    fun getFromCache(info: BitmapInfo): Bitmap? {
        return memoryCache.get(info)
    }

    fun addToCache(info: BitmapInfo, bitmap: Bitmap?) {
        memoryCache.put(info, bitmap)
    }


    data class BitmapInfo(
        val uri: Uri,
        val reqWidth: Int,
        val reqHeight: Int,
    )
}