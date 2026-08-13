@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package me.rosuh.easywatermark.render

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.ImageBitmapConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * R1 budget gates for ~50-image editor sessions.
 *
 * Production caps (2026-08-12 R1): watermarked 48 entries / 48 MiB purpose + 64 MiB joint.
 * At 720, sequential scrub still cannot hold all 50 (byte math), but focus+±2 at 1080 fits
 * without thrashing the just-painted focus. Filmstrip purpose capacity is orthogonal — Coil
 * owns product UI thumbs (R3).
 */
class IosPreviewFiftyImageBudgetTest {

    @Test
    fun fiftyWatermarked720_keepsAtMostEntryAndByteCaps_andEvictsEarlyPaths() = runTest {
        val repo = repository()
        val edge = PreviewResolutionPolicy.BUCKET_720
        val bmp = square(edge)
        val bytesPer = IosPreviewImageRepository.approxBytes(bmp)
        val entryCap = IosPreviewImageRepository.DEFAULT_WATERMARKED_ENTRIES_MAX
        val byteCap = IosPreviewImageRepository.DEFAULT_WATERMARKED_BYTES_MAX
        val effectiveByBytes = (byteCap / bytesPer).toInt()
        assertTrue(
            effectiveByBytes < 50,
            "sanity: 50×${edge}px bitmaps cannot all fit watermarked byte budget",
        )
        // R1: entry cap (48) and 720 byte floor (~23) — byte still tighter than entry.
        assertTrue(
            effectiveByBytes <= entryCap,
            "at ${edge}px, byte cap ($effectiveByBytes) must be ≤ entry cap $entryCap",
        )
        // R1: dozens of 720 frames must fit (was ~8 under 16 MiB).
        assertTrue(
            effectiveByBytes >= 20,
            "R1 must retain ≥20×${edge}px WM frames by bytes (got $effectiveByBytes)",
        )

        for (i in 0 until 50) {
            repo.putForTests(
                IosPreviewKey("/tmp/ewm_src_$i", edge, IosPreviewPurpose.Watermarked),
                bmp,
            )
        }
        val snap = repo.snapshot()
        assertTrue(
            snap.watermarkedEntries <= entryCap,
            "entry cap violated: ${snap.watermarkedEntries} > $entryCap",
        )
        assertTrue(
            snap.watermarkedBytes <= byteCap,
            "byte cap violated: ${snap.watermarkedBytes} > $byteCap",
        )
        assertTrue(
            snap.watermarkedEntries <= effectiveByBytes + 0,
            "expected ≤$effectiveByBytes surviving ${edge}px WM frames, got ${snap.watermarkedEntries}",
        )
        assertNull(
            repo.cached(IosPreviewKey("/tmp/ewm_src_0", edge, IosPreviewPurpose.Watermarked)),
            "image 0 must not survive a 50-image sequential visit under production budgets",
        )
        assertTrue(
            repo.cached(IosPreviewKey("/tmp/ewm_src_49", edge, IosPreviewPurpose.Watermarked)) != null,
            "most-recent focus must remain cached",
        )
        // Mid-session path near the tail of the retained window should still be present.
        assertNotNull(
            repo.cached(
                IosPreviewKey(
                    "/tmp/ewm_src_${49 - (effectiveByBytes / 2).coerceAtLeast(1)}",
                    edge,
                    IosPreviewPurpose.Watermarked,
                ),
            ),
            "recent scrub history inside working set must survive",
        )
    }

    @Test
    fun neighborPrefetchWindow_at1080_fitsWithoutThrashingFocus() = runTest {
        // R1: focus + ±2 (5 frames) at 1080 must fit watermarked purpose + joint budgets.
        val repo = repository()
        val edge = PreviewResolutionPolicy.BUCKET_1080
        val bmp = square(edge)
        val bytesPer = IosPreviewImageRepository.approxBytes(bmp)
        val needForFive = bytesPer * 5
        assertTrue(
            needForFive <= IosPreviewImageRepository.DEFAULT_WATERMARKED_BYTES_MAX,
            "focus+±2 at ${edge}px must fit watermarkedBytesMax " +
                "(need=$needForFive cap=${IosPreviewImageRepository.DEFAULT_WATERMARKED_BYTES_MAX})",
        )
        assertTrue(
            needForFive <= IosPreviewImageRepository.SOURCE_AND_PREVIEW_BYTES_MAX,
            "focus+±2 must also fit joint source+preview budget",
        )

        val paths = listOf(10, 8, 9, 11, 12).map { "/tmp/ewm_src_$it" }
        for (p in paths) {
            repo.putForTests(IosPreviewKey(p, edge, IosPreviewPurpose.Watermarked), bmp)
        }
        val snap = repo.snapshot()
        assertTrue(snap.watermarkedBytes <= IosPreviewImageRepository.DEFAULT_WATERMARKED_BYTES_MAX)
        assertEquals(
            5,
            snap.watermarkedEntries,
            "prefetch of 5×${edge}px must keep all neighbors (got ${snap.watermarkedEntries})",
        )
        for (p in paths) {
            assertNotNull(
                repo.cached(IosPreviewKey(p, edge, IosPreviewPurpose.Watermarked)),
                "neighbor $p must remain after focus+±2 warm",
            )
        }
    }

    @Test
    fun fiftyFilmstrip128_fitsEntryCap_butRepoDoesNotServeCoilUiPath() = runTest {
        // Filmstrip purpose can hold ~48×128 thumbs under 8 MiB — capacity is fine for 50 if
        // UI read the repository. Production Ready cells use Coil ProductAsyncImage instead
        // (R3 / FiftyImageFilmstripSwitchDiagnosisTest). This test only proves repository
        // Filmstrip capacity is not the 50-image bottleneck by itself at 128px.
        val repo = repository()
        val edge = 128
        val bmp = square(edge)
        for (i in 0 until 50) {
            repo.putForTests(
                IosPreviewKey("/tmp/ewm_src_$i", edge, IosPreviewPurpose.Filmstrip),
                bmp,
            )
        }
        val snap = repo.snapshot()
        assertTrue(snap.filmstripBytes <= IosPreviewImageRepository.FILMSTRIP_BYTES_MAX)
        assertEquals(
            IosPreviewImageRepository.DEFAULT_FILMSTRIP_ENTRIES_MAX,
            snap.filmstripEntries,
            "50 puts at 128px must clamp to filmstrip entry cap 48",
        )
        assertNull(repo.cached(IosPreviewKey("/tmp/ewm_src_0", edge, IosPreviewPurpose.Filmstrip)))
        assertTrue(
            repo.cached(IosPreviewKey("/tmp/ewm_src_49", edge, IosPreviewPurpose.Filmstrip)) != null,
        )
    }

    private fun TestScope.repository(): IosPreviewImageRepository =
        IosPreviewImageRepository(
            ownerScope = CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher(testScheduler)),
        )

    private fun square(edge: Int): ImageBitmap =
        ImageBitmap(edge, edge, ImageBitmapConfig.Argb8888)
}
