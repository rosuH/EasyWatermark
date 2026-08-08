package me.rosuh.easywatermark.ui

/**
 * Progressive Pending-cell chrome timing (owner device verdict 2026-08-07 / Attempt 4).
 *
 * - Under [SILENT_UNTIL_MS]: blank cell only (no chrome).
 * - From [LOADING_FROM_MS]: centered indeterminate loading animation (geometry-neutral in 40dp).
 * - Reduced motion: static non-animated loading affordance from [SILENT_UNTIL_MS] — never continuous motion.
 * - No preparing/preview text label; no visible remove chip on Pending (a11y remove remains on the cell).
 */
internal object ProgressivePendingChrome {
    const val SILENT_UNTIL_MS: Long = 120L
    /** Alias kept for call sites that still name the post-silent threshold. */
    const val STATIC_FROM_MS: Long = 350L
    const val LOADING_FROM_MS: Long = STATIC_FROM_MS

    enum class Phase {
        /** No spinner, no label. */
        Silent,
        /** Centered indeterminate loading animation. */
        Loading,
        /** Reduced-motion: static non-animated loading mark (never continuous motion). */
        StaticLoading,
    }

    /**
     * @param elapsedMs time since the slot entered Pending for this generation
     * @param reduceMotion when true, [StaticLoading] at [SILENT_UNTIL_MS] (no delayed spinner path)
     */
    fun phase(elapsedMs: Long, reduceMotion: Boolean): Phase {
        if (elapsedMs < SILENT_UNTIL_MS) return Phase.Silent
        if (reduceMotion) return Phase.StaticLoading
        return if (elapsedMs >= LOADING_FROM_MS) Phase.Loading else Phase.Silent
    }
}
