package me.rosuh.easywatermark.render

import androidx.compose.ui.graphics.ImageBitmap
import me.rosuh.easywatermark.data.model.MediaRef
import me.rosuh.easywatermark.data.repo.IosIconPersistence
import platform.Foundation.NSLock

/**
 * Single-slot memo for the decoded Image-mode watermark icon.
 *
 * Not a third cache layer: the 水印预览缓存 (Source / Watermarked) still owns every photo frame.
 * This holds exactly **one** decoded icon, because only one icon is configured at a time, and it
 * exists because [IosPreviewRaster.renderWatermarked] otherwise did a full `NSData` file read plus
 * a decode on *every* compose — one filmstrip tap (focus + ±2) paid five of each, and so did every
 * config change and every CLAMP draft drag frame.
 *
 * Keyed by [MediaRef]: newly picked icons are persisted as `icon_<NSUUID>`, so a different icon is
 * always a different path. [invalidate] covers the case where an owned path is rewritten in place.
 */
/** J5: internal raster helper — not part of the Swift product API surface. */
internal object IosWatermarkIconCache {
    private val lock = NSLock()
    private var cachedRef: MediaRef? = null
    private var cachedMaxEdgePx: Int = 0
    private var cachedIcon: ImageBitmap? = null
    private var decodeCount: Int = 0

    /**
     * Decoded icon for [ref] bounded to [maxEdgePx].
     *
     * Propagates [IosIconPersistence.readIconBytes] failure exactly as the inline path did — an
     * unreadable icon must not silently compose as a watermark-less frame.
     */
    fun decoded(ref: MediaRef, maxEdgePx: Int): ImageBitmap {
        require(maxEdgePx > 0) { "IosWatermarkIconCache: non-positive icon max edge" }
        hit(ref, maxEdgePx)?.let { return it }
        // Decode outside the lock: a concurrent ±2 warm may duplicate this one decode rather than
        // block the raster thread, and the last writer wins with an equivalent bitmap.
        val bytes = IosIconPersistence.readIconBytes(ref)
        val decoded = IosImageDecoder.decodeThumbnail(bytes, maxEdgePx = maxEdgePx)
        lock.lock()
        try {
            decodeCount += 1
            cachedRef = ref
            cachedMaxEdgePx = maxEdgePx
            cachedIcon = decoded
        } finally {
            lock.unlock()
        }
        return decoded
    }

    private fun hit(ref: MediaRef, maxEdgePx: Int): ImageBitmap? {
        lock.lock()
        return try {
            if (cachedRef == ref && cachedMaxEdgePx == maxEdgePx) cachedIcon else null
        } finally {
            lock.unlock()
        }
    }

    /** Drop the memo (host trimCaches / dispose, or an owned icon path rewritten in place). */
    fun invalidate() {
        lock.lock()
        try {
            cachedRef = null
            cachedMaxEdgePx = 0
            cachedIcon = null
        } finally {
            lock.unlock()
        }
    }

    /** Test seam: how many real file-read + decode passes happened. */
    fun decodeCountForTests(): Int {
        lock.lock()
        return try {
            decodeCount
        } finally {
            lock.unlock()
        }
    }

    fun resetForTests() {
        lock.lock()
        try {
            cachedRef = null
            cachedMaxEdgePx = 0
            cachedIcon = null
            decodeCount = 0
        } finally {
            lock.unlock()
        }
    }
}
