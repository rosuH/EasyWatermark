package me.rosuh.easywatermark.utils

import android.graphics.Bitmap
import android.util.Size
import androidx.collection.LruCache
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * An easy bitmap cache pool base on [LruCache] with [Size] as Key
 * while the caller should clear the bitmap content if it was reused.
 * An Singleton pattern would be fine in this specified App.
 * @author rosuh@qq.com
 * @date 2021/8/16
 */
object TextBitmapCache {
    val maxCacheSize: Int
        get() {
            return (Runtime.getRuntime().maxMemory() / 1024).toInt() / 8
        }

    private val cache: LruCache<Size, Bitmap> by lazy {
        object : LruCache<Size, Bitmap>(maxCacheSize) {
            override fun sizeOf(key: Size, value: Bitmap): Int {
                return value.byteCount / 1024
            }

            override fun entryRemoved(
                evicted: Boolean,
                key: Size,
                oldValue: Bitmap,
                newValue: Bitmap?
            ) {
                if (oldValue.isRecycled || oldValue == newValue) {
                    return
                }
                oldValue.recycle()
            }

            override fun create(key: Size): Bitmap? {
                return Bitmap.createBitmap(key.width, key.height, Bitmap.Config.ARGB_8888)
            }
        }
    }

    private val mutex by lazy { Mutex() }

    suspend fun get(
        width: Int,
        height: Int,
    ): Bitmap {
        mutex.withLock {
            val size = Size(width, height)
            return cache.get(size)!!
        }
    }
}