package me.rosuh.easywatermark.render

import kotlin.concurrent.Volatile
import kotlin.experimental.ExperimentalObjCRefinement
import kotlin.native.HiddenFromObjC
import me.rosuh.easywatermark.data.model.MediaRef

/**
 * Single-slot memo for the decoded Image-mode watermark icon.
 *
 * Not a third photo cache: [PreviewImageRepository] still owns every photo frame.
 * Keyed by [MediaRef] + max edge. Decode is supplied by the platform caller.
 *
 * Product long-edge is [ICON_MAX_EDGE_PX] (256) — never the photo pane size.
 */
@OptIn(ExperimentalObjCRefinement::class)
@HiddenFromObjC
class WatermarkIconCache<T : Any> {
    private data class Slot<T>(
        val ref: MediaRef,
        val maxEdgePx: Int,
        val icon: T,
    )

    @Volatile private var slot: Slot<T>? = null
    @Volatile private var decodeCount: Int = 0

    /**
     * Decoded icon for [ref] bounded to [maxEdgePx].
     *
     * [decode] failure must propagate — an unreadable icon must not silently compose
     * as a watermark-less frame.
     */
    fun decoded(ref: MediaRef, maxEdgePx: Int, decode: () -> T): T {
        require(maxEdgePx > 0) { "WatermarkIconCache: non-positive icon max edge" }
        slot?.let { cached ->
            if (cached.ref == ref && cached.maxEdgePx == maxEdgePx) return cached.icon
        }
        val icon = decode()
        decodeCount += 1
        slot = Slot(ref, maxEdgePx, icon)
        return icon
    }

    fun invalidate() {
        slot = null
    }

    fun decodeCountForTests(): Int = decodeCount

    fun resetForTests() {
        slot = null
        decodeCount = 0
    }

    companion object {
        const val ICON_MAX_EDGE_PX: Int = 256
    }
}
