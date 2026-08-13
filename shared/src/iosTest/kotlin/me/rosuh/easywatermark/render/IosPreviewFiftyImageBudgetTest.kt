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
 * Working-set budget gates for ~50-image editor sessions.
 *
 * Purpose floors stay 12 / 48 MiB at 720. Live caps follow [PreviewWorkingSetBudget] so
 * focus + ±2 at the current preview long-edge (phone 1920) fit. Filmstrip purpose capacity
 * is orthogonal — Coil owns product UI thumbs (R3).
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
    fun neighborPrefetchWindow_atCurrentLongEdge_fitsWithoutThrashingFocus() = runTest {
        val edge = PreviewResolutionPolicy.PHONE_PREVIEW_MAX_LONG_EDGE_PX
        val caps = PreviewWorkingSetBudget.caps(edge, physicalMemoryBytes = 8L shl 30)
        val repo = repository()
        repo.applyWorkingSetCaps(caps)
        val bmp = fourByThree(edge)
        val bytesPer = IosPreviewImageRepository.approxBytes(bmp)
        val needForFive = bytesPer * 5
        assertTrue(
            needForFive <= caps.watermarkedBytesMax,
            "focus+±2 at ${edge}px must fit watermarkedBytesMax " +
                "(need=$needForFive cap=${caps.watermarkedBytesMax})",
        )
        assertTrue(
            needForFive <= caps.jointBytesMax,
            "focus+±2 must also fit joint source+preview budget",
        )

        val focus = "/tmp/ewm_src_10"
        val neighbors = listOf(8, 9, 11, 12).map { "/tmp/ewm_src_$it" }
        // Neighbors first, then the just-painted focus — ±2 must not evict it.
        for (p in neighbors) {
            repo.putForTests(IosPreviewKey(p, edge, IosPreviewPurpose.Watermarked), bmp)
        }
        repo.putForTests(IosPreviewKey(focus, edge, IosPreviewPurpose.Watermarked), bmp)
        assertNotNull(
            repo.cached(IosPreviewKey(focus, edge, IosPreviewPurpose.Watermarked)),
            "focus must survive ±2 prefetch",
        )
        for (p in neighbors) {
            assertNotNull(
                repo.cached(IosPreviewKey(p, edge, IosPreviewPurpose.Watermarked)),
                "neighbor $p must remain after focus+±2 warm",
            )
        }

        repo.putForTests(IosPreviewKey("/tmp/ewm_src_13", edge, IosPreviewPurpose.Watermarked), bmp)
        assertNull(
            repo.cached(IosPreviewKey(neighbors.first(), edge, IosPreviewPurpose.Watermarked)),
            "6th 4:3 frame at $edge must evict the oldest neighbor",
        )
        assertNotNull(
            repo.cached(IosPreviewKey(focus, edge, IosPreviewPurpose.Watermarked)),
            "focus must survive a 6th frame (oldest neighbor evicted first)",
        )
        assertNotNull(
            repo.cached(IosPreviewKey("/tmp/ewm_src_13", edge, IosPreviewPurpose.Watermarked)),
            "newest frame must remain after eviction",
        )
        val snap = repo.snapshot()
        assertTrue(snap.watermarkedBytes <= caps.watermarkedBytesMax)
        assertTrue(snap.watermarkedEntries <= 5)
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

    private fun fourByThree(longEdge: Int): ImageBitmap {
        val shortEdge = kotlin.math.ceil(longEdge * 3.0 / 4.0).toInt()
        return ImageBitmap(longEdge, shortEdge, ImageBitmapConfig.Argb8888)
    }
}
