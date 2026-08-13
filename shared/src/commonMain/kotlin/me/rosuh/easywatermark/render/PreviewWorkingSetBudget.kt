package me.rosuh.easywatermark.render

import kotlin.math.ceil

/**
 * Byte caps for the editor preview working set (focus + ±2).
 *
 * One 4:3 frame at the current preview long-edge, times five, with historical floors so a
 * 720 pane does not shrink below the R1 12 / 48 MiB purpose caps. Device memory only
 * **compresses** the joint when the formula would exceed the ceiling — it never invents a
 * third cache layer.
 */
internal object PreviewWorkingSetBudget {
    const val WORKING_SET_FRAMES: Int = 5
    const val SOURCE_BYTES_FLOOR: Long = 12L * 1024 * 1024
    const val WATERMARKED_BYTES_FLOOR: Long = 48L * 1024 * 1024
    const val LOW_MEMORY_JOINT_MAX: Long = 64L * 1024 * 1024
    const val HIGH_MEMORY_JOINT_MAX: Long = 128L * 1024 * 1024

    /** 3.5 GiB. Below this, joint is held at [LOW_MEMORY_JOINT_MAX]. */
    const val LOW_MEMORY_THRESHOLD_BYTES: Long = 3_758_096_384L

    fun bytesPerFrame(longEdgePx: Int): Long {
        val edge = longEdgePx.coerceAtLeast(1)
        val shortEdge = ceil(edge * 3.0 / 4.0).toLong()
        return edge.toLong() * shortEdge * 4L
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
        )
    }
}

internal data class PreviewWorkingSetCaps(
    val longEdgePx: Int,
    val sourceBytesMax: Long,
    val watermarkedBytesMax: Long,
    val jointBytesMax: Long,
)
