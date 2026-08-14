package me.rosuh.easywatermark.render

/**
 * Caps for the editor preview working set (focus + ±2).
 *
 * **Entry counts decide residency; bytes are only a memory fence.** Bytes used to decide both,
 * and because the byte formula modelled a 4:3 frame it under-counted every source at or above
 * 1:1 — so a square or 5:4 photo silently held 3 frames while the code promised 5, with no test
 * catching it because the fixture was 4:3. Sizing the fence for the worst aspect ratio and
 * capping entries at the actual working set makes the promise checkable and aspect-independent.
 *
 * Historical floors keep a 720 pane from shrinking below the R1 12 / 48 MiB purpose caps. Device
 * memory only **compresses** the joint when the formula would exceed the ceiling — it never
 * invents a third cache layer.
 */
internal object PreviewWorkingSetBudget {
    const val WORKING_SET_FRAMES: Int = 5
    const val SOURCE_BYTES_FLOOR: Long = 12L * 1024 * 1024
    const val WATERMARKED_BYTES_FLOOR: Long = 48L * 1024 * 1024
    const val LOW_MEMORY_JOINT_MAX: Long = 64L * 1024 * 1024
    const val HIGH_MEMORY_JOINT_MAX: Long = 128L * 1024 * 1024

    /** 3.5 GiB. Below this, joint is held at [LOW_MEMORY_JOINT_MAX]. */
    const val LOW_MEMORY_THRESHOLD_BYTES: Long = 3_758_096_384L

    /**
     * Worst-case ARGB bytes for one decoded frame whose long edge is [longEdgePx].
     *
     * Square, not 4:3: a fence has to assume the largest frame it can be asked to hold, and any
     * source at 1:1 or wider-than-tall-inverted (1:1, 5:4, panorama crops) reaches it.
     */
    fun bytesPerFrame(longEdgePx: Int): Long {
        val edge = longEdgePx.coerceAtLeast(1).toLong()
        return edge * edge * 4L
    }

    fun caps(longEdgePx: Int, physicalMemoryBytes: Long): PreviewWorkingSetCaps {
        val fiveFrames = WORKING_SET_FRAMES * bytesPerFrame(longEdgePx)
        var sourceBytesMax = maxOf(SOURCE_BYTES_FLOOR, fiveFrames)
        var watermarkedBytesMax = maxOf(WATERMARKED_BYTES_FLOOR, fiveFrames)
        var jointBytesMax = sourceBytesMax + watermarkedBytesMax
        val jointCeiling = if (physicalMemoryBytes < LOW_MEMORY_THRESHOLD_BYTES) {
            LOW_MEMORY_JOINT_MAX
        } else {
            HIGH_MEMORY_JOINT_MAX
        }
        if (jointBytesMax > jointCeiling) {
            val scale = jointCeiling.toDouble() / jointBytesMax.toDouble()
            sourceBytesMax = (sourceBytesMax * scale).toLong().coerceAtLeast(1L)
            watermarkedBytesMax = (watermarkedBytesMax * scale).toLong().coerceAtLeast(1L)
            jointBytesMax = (sourceBytesMax + watermarkedBytesMax).coerceAtMost(jointCeiling)
        }
        return PreviewWorkingSetCaps(
            longEdgePx = longEdgePx.coerceAtLeast(1),
            sourceBytesMax = sourceBytesMax,
            watermarkedBytesMax = watermarkedBytesMax,
            jointBytesMax = jointBytesMax,
            sourceEntriesMax = SOURCE_ENTRIES_MAX,
            watermarkedEntriesMax = WORKING_SET_FRAMES,
        )
    }

    /**
     * One more Source than the working set: a CLAMP drag decodes its draft at a shorter long edge,
     * so mid-gesture a sixth Source entry legitimately exists. Without the headroom the drag would
     * evict a neighbour's decode — the single most expensive thing to rebuild.
     */
    private val SOURCE_ENTRIES_MAX: Int = WORKING_SET_FRAMES + 1
}

internal data class PreviewWorkingSetCaps(
    val longEdgePx: Int,
    val sourceBytesMax: Long,
    val watermarkedBytesMax: Long,
    val jointBytesMax: Long,
    val sourceEntriesMax: Int,
    val watermarkedEntriesMax: Int,
)
