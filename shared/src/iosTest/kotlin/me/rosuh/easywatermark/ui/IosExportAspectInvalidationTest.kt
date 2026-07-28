package me.rosuh.easywatermark.ui

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.ImageBitmapConfig
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import me.rosuh.easywatermark.ui.save.freezeExportAspectRatio

/**
 * Production seam: cold Export sheet aspect ratios must observe cache fills.
 *
 * Ordinary LinkedHashMap puts do not invalidate Compose; [IosProductRootHost] bumps
 * [IosProductRootHost.exportAspectEpochForTests] so [itemAspectRatio] recomputes once,
 * then [freezeExportAspectRatio] keeps the first known ratio through export mutation.
 */
class IosExportAspectInvalidationTest {

    private fun bmp(w: Int, h: Int): ImageBitmap =
        ImageBitmap(w, h, ImageBitmapConfig.Argb8888)

    @Test
    fun coldCaches_nullUntilThumbLands_thenPortraitObserved() {
        val host = IosProductRootHost(
            onPickPhoto = {},
            onPickIcon = {},
            onShare = {},
            onSaveToPhotos = { _, done -> done(true, null) },
        )
        try {
            val path = "/tmp/ewm_export_portrait.jpg"
            val epoch0 = host.exportAspectEpochForTests()

            // Sheet opens before filmstrip/export decode — unknown → null (square freeze default).
            assertNull(host.resolveExportItemAspectRatio(path, infoWidth = 1, infoHeight = 1))

            // Cold Export thumb lands → epoch bumps and portrait ratio is visible.
            host.putExportThumbForTests(path, bmp(w = 100, h = 200), requestedMaxEdgePx = 216)
            assertTrue(host.exportAspectEpochForTests() > epoch0, "export decode must bump aspect epoch")
            val ratio = host.resolveExportItemAspectRatio(path, infoWidth = 1, infoHeight = 1)
            assertEquals(0.5f, ratio!!, absoluteTolerance = 0.001f)
        } finally {
            host.dispose()
        }
    }

    @Test
    fun filmstripPrefetchPath_alsoInvalidatesAspectEpoch() {
        val host = IosProductRootHost(
            onPickPhoto = {},
            onPickIcon = {},
            onShare = {},
            onSaveToPhotos = { _, done -> done(true, null) },
        )
        try {
            val path = "/tmp/ewm_export_landscape.jpg"
            val epoch0 = host.exportAspectEpochForTests()
            assertNull(host.resolveExportItemAspectRatio(path))

            host.putFilmstripThumbForTests(path, bmp(w = 200, h = 100))
            assertTrue(host.exportAspectEpochForTests() > epoch0, "filmstrip put must bump aspect epoch")
            assertEquals(
                2f,
                host.resolveExportItemAspectRatio(path)!!,
                absoluteTolerance = 0.001f,
            )
        } finally {
            host.dispose()
        }
    }

    @Test
    fun freeze_afterColdLand_ignoresLaterImageInfoMutation() {
        val host = IosProductRootHost(
            onPickPhoto = {},
            onPickIcon = {},
            onShare = {},
            onSaveToPhotos = { _, done -> done(true, null) },
        )
        try {
            val path = "content://mixed/portrait"
            val frozen = mutableMapOf<Any, Float>()

            // Cold open.
            var candidate = host.resolveExportItemAspectRatio(path, 1, 1)
            assertEquals(1f, freezeExportAspectRatio(frozen, path, candidate))

            // Thumb lands (portrait).
            host.putExportThumbForTests(path, bmp(100, 200))
            candidate = host.resolveExportItemAspectRatio(path, 1, 1)
            assertEquals(0.5f, freezeExportAspectRatio(frozen, path, candidate), absoluteTolerance = 0.001f)

            // Export pipeline mutates ImageInfo to landscape — freeze must hold portrait.
            candidate = host.resolveExportItemAspectRatio(path, infoWidth = 4000, infoHeight = 2000)
            assertEquals(0.5f, freezeExportAspectRatio(frozen, path, candidate), absoluteTolerance = 0.001f)
        } finally {
            host.dispose()
        }
    }

    @Test
    fun trimCaches_clearsAspectSourceAndBumpsEpoch() {
        val host = IosProductRootHost(
            onPickPhoto = {},
            onPickIcon = {},
            onShare = {},
            onSaveToPhotos = { _, done -> done(true, null) },
        )
        try {
            val path = "/tmp/ewm_trim.jpg"
            host.putExportThumbForTests(path, bmp(120, 80))
            assertEquals(1.5f, host.resolveExportItemAspectRatio(path)!!, absoluteTolerance = 0.001f)
            val epoch = host.exportAspectEpochForTests()
            host.trimCaches()
            assertTrue(host.exportAspectEpochForTests() > epoch)
            assertNull(host.resolveExportItemAspectRatio(path))
            assertEquals(0, host.cacheBudgetForTests().exportThumb)
        } finally {
            host.dispose()
        }
    }
}
