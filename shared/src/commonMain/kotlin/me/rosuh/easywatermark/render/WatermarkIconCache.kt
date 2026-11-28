package me.rosuh.easywatermark.render

import kotlin.experimental.ExperimentalObjCRefinement
import kotlin.native.HiddenFromObjC
import me.rosuh.easywatermark.data.model.MediaRef

/**
 * Bounded LRU memo for decoded Image-mode watermark icons.
 *
 * Not a third photo cache: [PreviewImageRepository] still owns every photo frame.
 * Keyed by [MediaRef] + max edge so switching among recently used custom/built-in
 * stickers does not re-read or re-decode on every tap. Decode is supplied by the
 * platform caller and runs outside [lock].
 *
 * Product long-edge is [ICON_MAX_EDGE_PX] (256) — never the photo pane size.
 */
@OptIn(ExperimentalObjCRefinement::class)
@HiddenFromObjC
class WatermarkIconCache<T : Any>(
    private val maxEntries: Int = DEFAULT_MAX_ENTRIES,
) {
    private data class Key(
        val ref: MediaRef,
        val maxEdgePx: Int,
    )

    private val lock = IconCacheLock()
    private val entries = LinkedHashMap<Key, T>()
    private var decodeCount: Int = 0

    init {
        require(maxEntries >= 1) { "WatermarkIconCache: maxEntries must be >= 1" }
    }

    /**
     * Decoded icon for [ref] bounded to [maxEdgePx].
     *
     * [decode] failure must propagate — an unreadable icon must not silently compose
     * as a watermark-less frame.
     */
    fun decoded(ref: MediaRef, maxEdgePx: Int, decode: () -> T): T {
        require(maxEdgePx > 0) { "WatermarkIconCache: non-positive icon max edge" }
        val key = Key(ref, maxEdgePx)
        val hit = lock.withLock {
            entries.remove(key)?.also { existing ->
                entries[key] = existing
            }
        }
        if (hit != null) return hit
        val icon = decode()
        return lock.withLock {
            entries.remove(key)?.let { existing ->
                entries[key] = existing
                return@withLock existing
            }
            decodeCount += 1
            entries[key] = icon
            while (entries.size > maxEntries) {
                val eldestKey = entries.entries.iterator().next().key
                entries.remove(eldestKey)
            }
            icon
        }
    }

    fun invalidate() {
        lock.withLock {
            entries.clear()
        }
    }

    fun decodeCountForTests(): Int = lock.withLock { decodeCount }

    fun resetForTests() {
        lock.withLock {
            entries.clear()
            decodeCount = 0
        }
    }

    companion object {
        const val ICON_MAX_EDGE_PX: Int = 256
        const val DEFAULT_MAX_ENTRIES: Int = 16
    }
}
