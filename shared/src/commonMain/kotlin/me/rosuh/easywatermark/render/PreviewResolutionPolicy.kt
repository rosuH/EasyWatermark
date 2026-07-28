package me.rosuh.easywatermark.render

/**
 * Shared **committed editor preview** long-edge buckets for iOS and Desktop.
 *
 * Inputs are measured preview-box dimensions in **pixels** (Compose constraints /
 * [androidx.compose.ui.layout.onSizeChanged]), not whole-window Dp.
 *
 * Two deliberate tiers:
 * - **Transient** (placeholder + active CLAMP draft): always [PLACEHOLDER_MAX_EDGE_PX] / [DRAFT_MAX_EDGE_PX] (720).
 * - **Committed** (load / config / selection / gesture-end): [committedMaxEdgePx] from the measured box.
 *
 * Buckets (exact product policy — do not change without an owner decision):
 * invalid/unmeasured or `<=720` → 720;
 * `721..1080` → 1080;
 * `1081..1440` → 1440;
 * above 1440 → capped 1920.
 *
 * Internal only — not a public cross-platform API surface.
 */
internal object PreviewResolutionPolicy {

    /** Fast source placeholder + active drag-draft long-edge bound. */
    const val PLACEHOLDER_MAX_EDGE_PX: Int = 720

    /** Alias of [PLACEHOLDER_MAX_EDGE_PX] for draft-call sites. */
    const val DRAFT_MAX_EDGE_PX: Int = PLACEHOLDER_MAX_EDGE_PX

    /** Smallest committed bucket (also the transient tier). */
    const val BUCKET_720: Int = 720

    const val BUCKET_1080: Int = 1080

    const val BUCKET_1440: Int = 1440

    /** Hard cap for committed previews (not final export). */
    const val BUCKET_1920: Int = 1920

    /**
     * Map measured preview-box width/height (px) to a committed long-edge bucket.
     * Uses the longer side so Fit-letterboxed content still tracks the display scale.
     */
    fun committedMaxEdgePx(previewBoxWidthPx: Int, previewBoxHeightPx: Int): Int {
        if (previewBoxWidthPx <= 0 || previewBoxHeightPx <= 0) {
            return BUCKET_720
        }
        val longEdge = maxOf(previewBoxWidthPx, previewBoxHeightPx)
        return when {
            longEdge <= 720 -> BUCKET_720
            longEdge <= 1080 -> BUCKET_1080
            longEdge <= 1440 -> BUCKET_1440
            else -> BUCKET_1920
        }
    }

    /**
     * Max edge for a single preview paint: drafts stay at 720; committed uses [committedBucketPx].
     */
    fun maxEdgeForPaint(isDraft: Boolean, committedBucketPx: Int): Int {
        if (isDraft) return DRAFT_MAX_EDGE_PX
        val bucket = committedBucketPx
        return when {
            bucket <= 0 -> BUCKET_720
            bucket <= 720 -> BUCKET_720
            bucket <= 1080 -> BUCKET_1080
            bucket <= 1440 -> BUCKET_1440
            else -> BUCKET_1920
        }
    }
}
