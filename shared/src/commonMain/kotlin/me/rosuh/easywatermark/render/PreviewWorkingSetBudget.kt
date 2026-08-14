package me.rosuh.easywatermark.render

/**
 * Caps for the editor preview working set (focus + ±2).
 *
 * **Entry counts decide residency; bytes are only a memory fence.** Bytes used to decide both,
 * and because the byte formula modelled a 4:3 frame it under-counted every source at or above
 * 1:1 — so a square or 5:4 photo silently held 3 frames while the code promised 5, with no test
 * catching it because the fixture was 4:3. Sizing the fence for the worst aspect ratio and
 * capping entries at the actual working set makes the promise checkable.
 *
 * Checkable, not aspect-independent: at the phone 1920 cap the compressed 64 MiB per-purpose fence
 * still binds before [PreviewWorkingSetCaps.sourceEntriesMax], so residency is 4 frames at 1:1 and
 * 5 at 5:4 or wider. Square is the one aspect that does not reach focus + ±2, and reaching it would
 * mean raising [HIGH_MEMORY_JOINT_MAX] to ~141 MiB — an owner call, not a silent one. See
 * `PreviewWorkingSetBudgetTest.squareFence_worstCaseResidentBytes_areClampedByTheJointCeiling`.
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
     * Square, not 4:3. For a fixed long edge the area is `longEdge × shortEdge`, so it peaks when
     * the short edge equals the long edge — 1:1 is the most expensive shape a frame can be, and
     * every other aspect ratio (4:3, 5:4, and panoramas most of all) costs strictly less. A fence
     * has to assume the largest frame it can be asked to hold, which is why it sits at 1:1.
     *
     * This is **larger** than the old 4:3 model by 4/3, so it raises every byte cap. That is
     * deliberate — the old model under-counted real frames and silently evicted them — and it is
     * bounded: see [caps] for the ceiling that clamps the result.
     */
    fun bytesPerFrame(longEdgePx: Int): Long {
        val edge = longEdgePx.coerceAtLeast(1).toLong()
        return edge * edge * 4L
    }

    /**
     * Per-purpose and joint byte fences for [longEdgePx], clamped by device memory.
     *
     * Worst-case resident bytes at the phone 1920 cap, high-memory: each purpose is fenced at
     * 64 MiB and the joint at 128 MiB, so six square sources (6 × 14.7 MiB = 88 MiB) can **not**
     * go resident — the per-purpose fence evicts to four, and the joint fence holds
     * Source + Watermarked + ExportThumbnail at 128 MiB total. On a device below
     * [LOW_MEMORY_THRESHOLD_BYTES] those become 32 MiB per purpose and 64 MiB joint (two square
     * sources). So the entry headroom is bounded by bytes rather than trusted.
     *
     * The honest cost: moving the fence from 4:3 to 1:1 raises worst-case resident non-filmstrip
     * bytes at 1920 from 105.5 MiB to 128 MiB. That is a real increase on a platform that gets
     * jetsammed, accepted because the alternative was evicting frames the code promised to keep,
     * and it stays at the already-approved 128 MiB ceiling rather than above it.
     */
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
