package me.rosuh.easywatermark.ui

import androidx.compose.ui.graphics.ImageBitmap
import me.rosuh.easywatermark.render.IosByteArrayInterop
import me.rosuh.easywatermark.render.IosImageDecoder
import platform.Foundation.NSData
import platform.Foundation.dataWithContentsOfFile
import kotlin.math.max

/**
 * Bounded, constraint-driven Export-sheet thumbnail decode targets for iOS.
 *
 * Measured Compose pixel size → smallest documented bucket not below need, capped at the
 * maximum card long-edge the waterfall layout can render (72dp × 4 height @ dense scale).
 * Cache entries carry the requested max-edge so an undersized path-only hit cannot be reused
 * after the measured requirement grows; a naturally smaller source does not keep upgrading.
 */
internal object IosExportThumbnailLoader {

    /**
     * Documented decode buckets (long edge, px).
     * Upper bound 864 covers 72dp-wide × 1:4-tall cards at 3x (216×864).
     */
    val Buckets: IntArray = intArrayOf(96, 144, 216, 288, 384, 576, 864)

    /** Hard upper cap — matches max layout long edge, not an arbitrary undersize. */
    val MaxBucketPx: Int = Buckets.last()

    data class Entry(
        val bitmap: ImageBitmap,
        /** maxEdgePx passed to [IosImageDecoder.decodeThumbnail] when this entry was built. */
        val requestedMaxEdgePx: Int,
    ) {
        val bitmapLongEdgePx: Int
            get() = max(bitmap.width, bitmap.height)
    }

    /**
     * Map measured layout pixels to a decode long-edge target.
     * Zero/negative measurements yield 0 (caller must wait for a real size).
     */
    fun resolveMaxEdgePx(measuredWidthPx: Int, measuredHeightPx: Int): Int {
        // Any non-positive edge means "not yet measured" / invalid — do not decode yet.
        if (measuredWidthPx <= 0 || measuredHeightPx <= 0) return 0
        val need = max(measuredWidthPx, measuredHeightPx)
        val capped = need.coerceAtMost(MaxBucketPx)
        for (b in Buckets) {
            if (b >= capped) return b
        }
        return MaxBucketPx
    }

    /**
     * Whether [entry] already covers [neededMaxEdgePx].
     *
     * - Missing entry → not sufficient for a positive need.
     * - need ≤ 0 → treat as sufficient (not yet measured; keep showing current bitmap).
     * - Bitmap smaller than its own request → source is naturally small → sufficient.
     * - Otherwise require the original request (or bitmap edge) ≥ needed.
     */
    fun isSufficient(entry: Entry?, neededMaxEdgePx: Int): Boolean {
        if (entry == null) return false
        if (neededMaxEdgePx <= 0) return true
        if (entry.bitmapLongEdgePx < entry.requestedMaxEdgePx) return true
        return entry.requestedMaxEdgePx >= neededMaxEdgePx ||
            entry.bitmapLongEdgePx >= neededMaxEdgePx
    }

    /**
     * Decode a file path to an [Entry], or null on missing/corrupt/unsupported data.
     * Failures stay closed — never throw into produceState.
     */
    fun decodeFileOrNull(path: String, maxEdgePx: Int): Entry? {
        if (path.isBlank() || maxEdgePx <= 0) return null
        val data = NSData.dataWithContentsOfFile(path) ?: return null
        return runCatching {
            val bmp = IosImageDecoder.decodeThumbnail(
                IosByteArrayInterop.fromNSData(data),
                maxEdgePx = maxEdgePx,
            )
            Entry(bitmap = bmp, requestedMaxEdgePx = maxEdgePx)
        }.getOrNull()
    }

    fun approxBytes(entry: Entry): Long =
        IosHostImageCacheBudgets.approxBytes(entry.bitmap)

    fun totalApproxBytes(map: Map<String, Entry>): Long =
        map.values.sumOf { approxBytes(it) }

    /**
     * FIFO entry + byte eviction matching [IosHostImageCacheBudgets.enforce] semantics
     * for the Export-thumb map of [Entry] values.
     */
    fun enforce(
        map: MutableMap<String, Entry>,
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
