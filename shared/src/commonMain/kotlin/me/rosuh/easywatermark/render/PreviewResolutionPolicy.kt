package me.rosuh.easywatermark.render

import kotlin.math.ceil

/**
 * Bounded, container-driven iOS editor decode policy.
 *
 * The policy is intentionally internal: it is an implementation detail of the iOS preview edge,
 * not a new Shared.framework API. Final export never uses these bounds.
 */
internal object PreviewResolutionPolicy {
    const val PLACEHOLDER_MAX_EDGE_PX: Int = 720
    const val DRAFT_MAX_EDGE_PX: Int = PLACEHOLDER_MAX_EDGE_PX
    const val BUCKET_720: Int = 720
    const val BUCKET_1080: Int = 1080
    const val BUCKET_1440: Int = 1440
    const val BUCKET_1920: Int = 1920

    /**
     * ContentScale.Fit's displayed long edge with 10% headroom, rounded into the approved
     * committed buckets.  Invalid metadata/constraints intentionally falls back to the safe 720.
     */
    fun committedMaxEdgePxForFit(
        sourceWidthPx: Int,
        sourceHeightPx: Int,
        containerWidthPx: Int,
        containerHeightPx: Int,
    ): Int {
        if (
            sourceWidthPx <= 0 || sourceHeightPx <= 0 ||
            containerWidthPx <= 0 || containerHeightPx <= 0
        ) {
            return BUCKET_720
        }
        val scale = minOf(
            containerWidthPx.toDouble() / sourceWidthPx.toDouble(),
            containerHeightPx.toDouble() / sourceHeightPx.toDouble(),
        )
        val displayLongEdge = maxOf(sourceWidthPx, sourceHeightPx) * scale
        return bucketForLongEdge(ceil(displayLongEdge * 1.10).toInt())
    }

    /** Actual 40dp filmstrip cell pixels map to a bounded native thumbnail request. */
    fun filmstripMaxEdgePx(measuredCellPx: Int): Int = when {
        measuredCellPx <= 128 -> 128
        measuredCellPx <= 160 -> 160
        else -> 192
    }

    fun maxEdgeForPaint(isDraft: Boolean, committedBucketPx: Int): Int =
        if (isDraft) DRAFT_MAX_EDGE_PX else bucketForLongEdge(committedBucketPx)

    private fun bucketForLongEdge(longEdgePx: Int): Int = when {
        longEdgePx <= BUCKET_720 -> BUCKET_720
        longEdgePx <= BUCKET_1080 -> BUCKET_1080
        longEdgePx <= BUCKET_1440 -> BUCKET_1440
        else -> BUCKET_1920
    }
}
