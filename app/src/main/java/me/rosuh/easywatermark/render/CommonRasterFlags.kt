package me.rosuh.easywatermark.render

/**
 * ADR-0018 / C2 feature flags for Android common 光栅 routing.
 *
 * **P3.5 (2026-07-13):** preview **and** export default **on** for debug **and** release.
 * Native [WatermarkRenderer] builders remain the flag-off fallback only (Gate 4 delete later).
 *
 * Tests may flip these to measure dual-path (native vs common) or force the legacy path.
 */
object CommonRasterFlags {
    @Volatile
    var useCommonRasterPreview: Boolean = true

    @Volatile
    var useCommonRasterExport: Boolean = true
}
