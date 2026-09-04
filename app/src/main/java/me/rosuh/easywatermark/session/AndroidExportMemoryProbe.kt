package me.rosuh.easywatermark.session

/**
 * H2 test seam: counts intermediate bitmap early-release events on the production
 * [AndroidExportPipelinePort] path. Production leaves counters unused; tests reset/assert.
 *
 * Not a product API and not an H3 SLO.
 */
object AndroidExportMemoryProbe {
    @Volatile
    var sourceReleasedAfterComposeCount: Int = 0
        private set

    @Volatile
    var composedReleasedAfterEncodeCount: Int = 0
        private set

    fun reset() {
        sourceReleasedAfterComposeCount = 0
        composedReleasedAfterEncodeCount = 0
    }

    fun onSourceReleasedAfterCompose() {
        sourceReleasedAfterComposeCount++
    }

    fun onComposedReleasedAfterEncode() {
        composedReleasedAfterEncodeCount++
    }
}
