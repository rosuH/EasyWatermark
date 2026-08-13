package me.rosuh.easywatermark.render

import kotlin.math.ceil

/**
 * Shared **on-screen editor preview** decode bounds (iOS + Desktop; Android uses its own canvas path).
 *
 * ## Strategy (product)
 *
 * | Path | Max long edge | When |
 * |---|---|---|
 * | **Draft** (active CLAMP drag) | [DRAFT_MAX_EDGE_PX] = 720 | Prefer latency over sharpness while dragging |
 * | **Committed** (idle / config settle) | Display-driven bucket | Sharp enough for the **Fit** rect on screen |
 * | **Final export / Save** | Full source (no these bounds) | Platform export spine |
 *
 * Committed decode is **not** full-res export. It is “enough pixels so ContentScale.Fit does not
 * upscale a soft bitmap on the current display.”
 *
 * ## How the committed edge is chosen
 *
 * 1. Prefer [committedMaxEdgePxForFit]: ContentScale.Fit displayed long edge × 10% headroom,
 *    using **source** width/height and **container** size in **physical px** (density-applied).
 * 2. Fallback [committedMaxEdgePx]: max(containerW, containerH) when source dims are unknown.
 * 3. Snap into buckets via [bucketForLongEdge] (720 / 1080 / 1440 / 1920 / **2560** / **3840**).
 * 4. [maxEdgeForPaint]: draft → [draftMaxEdgePx] (720, or ≥1080 when committed is large); committed → re-bucket.
 *
 * Decode implementations already **keep full source** when source long edge ≤ requested maxEdge
 * (no artificial upscale at decode). Softness then is source-limited (e.g. 480px sample on a
 * 2k preview pane).
 *
 * ## Large screens / Retina
 *
 * Hosts must pass **density-aware** container px (`Dp.toPx()` or Compose `onSizeChanged` IntSize,
 * which is already in layout pixels). Capping only at 1920 under-sampled multi-k desktop panes;
 * buckets now go to **3840** so large dual-pane Desktop stays near 1:1 with the Fit rect.
 *
 * Final export never uses this policy.
 */
internal object PreviewResolutionPolicy {
    const val PLACEHOLDER_MAX_EDGE_PX: Int = 720
    const val DRAFT_MAX_EDGE_PX: Int = PLACEHOLDER_MAX_EDGE_PX
    const val BUCKET_720: Int = 720
    const val BUCKET_1080: Int = 1080
    const val BUCKET_1440: Int = 1440
    const val BUCKET_1920: Int = 1920
    /** Large tablet landscape / Desktop dual-pane on 2× displays. */
    const val BUCKET_2560: Int = 2560
    /** Large Desktop / 4K-class preview panes (still below full export). */
    const val BUCKET_3840: Int = 3840

    /**
     * Phone idle-preview long-edge ceiling. Export stays full-res.
     * Decode keeps source aspect; this only caps the longer side.
     */
    const val PHONE_PREVIEW_MAX_LONG_EDGE_PX: Int = BUCKET_1920

    /**
     * Map measured preview-box width/height (px) to a committed long-edge bucket.
     * Uses the longer side so Fit-letterboxed content still tracks the display scale.
     * Desktop/iOS preview hosts that only know the box size (not source pixels) use this.
     */
    fun committedMaxEdgePx(
        previewBoxWidthPx: Int,
        previewBoxHeightPx: Int,
        maxLongEdgePx: Int = BUCKET_3840,
    ): Int {
        if (previewBoxWidthPx <= 0 || previewBoxHeightPx <= 0) {
            return BUCKET_720
        }
        return clampToMaxLongEdge(
            bucketForLongEdge(maxOf(previewBoxWidthPx, previewBoxHeightPx)),
            maxLongEdgePx,
        )
    }

    /**
     * ContentScale.Fit's displayed long edge with 10% headroom, rounded into the approved
     * committed buckets.  Invalid metadata/constraints intentionally falls back to the safe 720.
     *
     * Prefer this when source width/height are known (import-time or decode header).
     * When source is smaller than the Fit rect, the decode path keeps native pixels (no upscale);
     * the bucket still reflects display need for sources large enough to fill the pane.
     */
    fun committedMaxEdgePxForFit(
        sourceWidthPx: Int,
        sourceHeightPx: Int,
        containerWidthPx: Int,
        containerHeightPx: Int,
        maxLongEdgePx: Int = BUCKET_3840,
    ): Int {
        if (
            sourceWidthPx <= 0 || sourceHeightPx <= 0 ||
            containerWidthPx <= 0 || containerHeightPx <= 0
        ) {
            return BUCKET_720
        }
        // Unknown/default ImageInfo (1×1) → treat as missing source metadata.
        if (sourceWidthPx <= 1 && sourceHeightPx <= 1) {
            return committedMaxEdgePx(containerWidthPx, containerHeightPx, maxLongEdgePx)
        }
        val scale = minOf(
            containerWidthPx.toDouble() / sourceWidthPx.toDouble(),
            containerHeightPx.toDouble() / sourceHeightPx.toDouble(),
        )
        val displayLongEdge = maxOf(sourceWidthPx, sourceHeightPx) * scale
        return clampToMaxLongEdge(
            bucketForLongEdge(ceil(displayLongEdge * 1.10).toInt()),
            maxLongEdgePx,
        )
    }

    private fun clampToMaxLongEdge(edgePx: Int, maxLongEdgePx: Int): Int {
        val cap = bucketForLongEdge(maxLongEdgePx.coerceAtLeast(BUCKET_720))
        return edgePx.coerceAtMost(cap)
    }

    /** Actual 40dp filmstrip cell pixels map to a bounded native thumbnail request. */
    fun filmstripMaxEdgePx(measuredCellPx: Int): Int = when {
        measuredCellPx <= 128 -> 128
        measuredCellPx <= 160 -> 160
        else -> 192
    }

    fun maxEdgeForPaint(isDraft: Boolean, committedBucketPx: Int): Int =
        if (isDraft) draftMaxEdgePx(committedBucketPx) else bucketForLongEdge(committedBucketPx)

    /**
     * Draft long-edge while CLAMP-dragging (F1).
     * Large committed panes (≥1080) keep draft ≥1080 so Desktop dual-pane is not soft-720-only.
     */
    fun draftMaxEdgePx(committedBucketPx: Int = 0): Int {
        val committed = bucketForLongEdge(committedBucketPx)
        return if (committed >= BUCKET_1080) {
            maxOf(DRAFT_MAX_EDGE_PX, BUCKET_1080).coerceAtMost(committed)
        } else {
            DRAFT_MAX_EDGE_PX
        }
    }

    /**
     * Snap a positive long-edge need into a decode budget.
     * Above 3840 we still cap at 3840 — export remains the full-res path.
     */
    fun bucketForLongEdge(longEdgePx: Int): Int = when {
        longEdgePx <= 0 -> BUCKET_720
        longEdgePx <= BUCKET_720 -> BUCKET_720
        longEdgePx <= BUCKET_1080 -> BUCKET_1080
        longEdgePx <= BUCKET_1440 -> BUCKET_1440
        longEdgePx <= BUCKET_1920 -> BUCKET_1920
        longEdgePx <= BUCKET_2560 -> BUCKET_2560
        else -> BUCKET_3840
    }
}
