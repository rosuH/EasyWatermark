package me.rosuh.easywatermark.ui.save

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Export waterfall layout contract: fixed card width, aspect clamp, freeze-first-known.
 */
class SaveExportPreviewBoxTest {

    @Test
    fun cardWidthToken_is72dp() {
        assertEquals(72, ExportWaterfallCardWidth.value.toInt())
    }

    @Test
    fun maxHeight_boundsViewportBelowPhoneSheetChrome() {
        // Must stay well under a typical phone sheet body so CTA remains reachable.
        assertTrue(ExportWaterfallMaxHeight.value >= 200f)
        assertTrue(ExportWaterfallMaxHeight.value <= 360f)
    }

    @Test
    fun aspectRatioOrNull_usesSourceDimensions() {
        assertEquals(1f, exportCardAspectRatioOrNull(100, 100)!!, absoluteTolerance = 0.001f)
        assertEquals(2f, exportCardAspectRatioOrNull(200, 100)!!, absoluteTolerance = 0.001f)
        assertEquals(0.5f, exportCardAspectRatioOrNull(100, 200)!!, absoluteTolerance = 0.001f)
    }

    @Test
    fun aspectRatioOrNull_treatsImageInfoDefault1x1AsUnknown() {
        // Production ImageInfo defaults stay 1×1 until export mutates them.
        assertNull(exportCardAspectRatioOrNull(1, 1))
        assertNull(exportCardAspectRatioOrNull(0, 100))
        assertNull(exportCardAspectRatioOrNull(100, 0))
        assertNull(exportCardAspectRatioOrNull(-4, 10))
    }

    @Test
    fun aspectRatioOrNull_clampsExtreme() {
        assertEquals(
            ExportAspectRatioMax,
            exportCardAspectRatioOrNull(10_000, 100)!!,
            absoluteTolerance = 0.001f,
        )
        assertEquals(
            ExportAspectRatioMin,
            exportCardAspectRatioOrNull(100, 10_000)!!,
            absoluteTolerance = 0.001f,
        )
    }

    @Test
    fun resolve_prefersPrimaryThenFallback() {
        // Primary unknown (1×1) → thumb dims win.
        assertEquals(
            0.5f,
            resolveExportCardAspectRatio(
                primaryWidth = 1,
                primaryHeight = 1,
                fallbackWidth = 100,
                fallbackHeight = 200,
            )!!,
            absoluteTolerance = 0.001f,
        )
        // Primary known wins even if fallback differs.
        assertEquals(
            2f,
            resolveExportCardAspectRatio(
                primaryWidth = 200,
                primaryHeight = 100,
                fallbackWidth = 100,
                fallbackHeight = 200,
            )!!,
            absoluteTolerance = 0.001f,
        )
        assertNull(resolveExportCardAspectRatio(1, 1, 0, 0))
    }

    @Test
    fun freeze_firstKnownWinsAcrossReadyIngSuccess() {
        val frozen = mutableMapOf<Any, Float>()
        val key = "content://portrait"

        // Pre-export: unknown → temporary square (layout default until thumb lands).
        assertEquals(1f, freezeExportAspectRatio(frozen, key, candidate = null))

        // Thumb arrives with portrait ratio — first real value freezes.
        val portrait = freezeExportAspectRatio(frozen, key, candidate = 0.5f)
        assertEquals(0.5f, portrait, absoluteTolerance = 0.001f)

        // Export mutates ImageInfo to landscape — freeze must ignore it.
        val afterExport = freezeExportAspectRatio(frozen, key, candidate = 2f)
        assertEquals(0.5f, afterExport, absoluteTolerance = 0.001f)

        // Recycle / Success path re-reads same key → same ratio.
        assertEquals(0.5f, freezeExportAspectRatio(frozen, key, candidate = null))
    }

    @Test
    fun freeze_mixedPortraitLandscape_stayDistinct() {
        val frozen = mutableMapOf<Any, Float>()
        val a = freezeExportAspectRatio(frozen, "a", 0.5f)
        val b = freezeExportAspectRatio(frozen, "b", 2f)
        assertTrue(a < 1f && b > 1f, "mixed selection must not all be square")
        assertEquals(a, freezeExportAspectRatio(frozen, "a", 1f))
        assertEquals(b, freezeExportAspectRatio(frozen, "b", 1f))
    }
}
