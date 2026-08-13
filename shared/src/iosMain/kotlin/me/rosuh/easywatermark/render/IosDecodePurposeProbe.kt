package me.rosuh.easywatermark.render

import platform.Foundation.NSLock
import platform.Foundation.NSThread

/**
 * Test-visible counters for **who** called ImageIO thumbnail decode.
 *
 * Diagnosis 2026-08-12: filmstrip UI uses Coil [ProductThumb] while host import still warms
 * [IosPreviewPurpose.Filmstrip] via a separate path — both end in [IosImageIODecoder.decodeThumbnail].
 * These counters prove double-work at runtime (not source-string contracts).
 *
 * Production behavior is unchanged: counters are never read outside tests / IosPreviewBench-style logs.
 */
internal object IosDecodePurposeProbe {
    enum class Purpose {
        /** [IosProductRootHost] filmstrip repository prefetch / ensureFocus. */
        FilmstripRepo,

        /** Coil [ProductThumbFetcher] product UI thumbs. */
        ProductThumbCoil,

        /** [IosPreviewRaster.renderWatermarked] background decode. */
        WatermarkedPreview,

        /** Source placeholder decode (no watermark). */
        SourcePlaceholder,
    }

    private val lock = NSLock()
    private val counts = IntArray(Purpose.entries.size)
    private var watermarkedOnMain = 0

    data class Snapshot(
        val filmstripRepo: Int,
        val productThumbCoil: Int,
        val watermarkedPreview: Int,
        val sourcePlaceholder: Int,
        val watermarkedOnMain: Int = 0,
    ) {
        val dualFilmstripDecodeTotal: Int get() = filmstripRepo + productThumbCoil
    }

    fun resetForTests() {
        lock.lock()
        try {
            counts.fill(0)
            watermarkedOnMain = 0
        } finally {
            lock.unlock()
        }
    }

    fun record(purpose: Purpose) {
        val onMain = NSThread.isMainThread
        lock.lock()
        try {
            counts[purpose.ordinal] += 1
            if (purpose == Purpose.WatermarkedPreview && onMain) {
                watermarkedOnMain += 1
            }
        } finally {
            lock.unlock()
        }
    }

    fun snapshotForTests(): Snapshot {
        lock.lock()
        return try {
            Snapshot(
                filmstripRepo = counts[Purpose.FilmstripRepo.ordinal],
                productThumbCoil = counts[Purpose.ProductThumbCoil.ordinal],
                watermarkedPreview = counts[Purpose.WatermarkedPreview.ordinal],
                sourcePlaceholder = counts[Purpose.SourcePlaceholder.ordinal],
                watermarkedOnMain = watermarkedOnMain,
            )
        } finally {
            lock.unlock()
        }
    }
}
