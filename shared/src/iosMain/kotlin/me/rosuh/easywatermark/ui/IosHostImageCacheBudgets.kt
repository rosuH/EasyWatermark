package me.rosuh.easywatermark.ui

import androidx.compose.ui.graphics.ImageBitmap

/**
 * H2: approximate byte accounting + eviction helpers for iOS host image caches.
 *
 * Engineering budgets (not H3 release SLOs). Eviction is FIFO by [LinkedHashMap] insertion
 * order (same as G4 entry-count policy). Keys must remain correct after eviction —
 * callers re-decode on miss.
 */
internal object IosHostImageCacheBudgets {

    /** Approximate ARGB_8888 storage for a Compose [ImageBitmap]. */
    fun approxBytes(bitmap: ImageBitmap): Long {
        val w = bitmap.width.toLong().coerceAtLeast(0)
        val h = bitmap.height.toLong().coerceAtLeast(0)
        return w * h * 4L
    }

    fun totalApproxBytes(map: Map<String, ImageBitmap>): Long =
        map.values.sumOf { approxBytes(it) }

    /**
     * Evict oldest entries until [map] is within [maxEntries] and [maxBytes].
     * @return number of entries removed.
     */
    fun enforce(
        map: MutableMap<String, ImageBitmap>,
        maxEntries: Int,
        maxBytes: Long,
    ): Int {
        var removed = 0
        while (map.size > maxEntries) {
            val oldest = map.keys.firstOrNull() ?: break
            map.remove(oldest)
            removed++
        }
        var bytes = totalApproxBytes(map)
        while (bytes > maxBytes && map.isNotEmpty()) {
            val oldest = map.keys.firstOrNull() ?: break
            val gone = map.remove(oldest)
            if (gone != null) {
                bytes -= approxBytes(gone)
                removed++
            } else {
                break
            }
        }
        return removed
    }
}
