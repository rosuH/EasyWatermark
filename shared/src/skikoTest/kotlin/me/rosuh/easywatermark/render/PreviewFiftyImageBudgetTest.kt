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

class PreviewFiftyImageBudgetTest {

    @Test
    fun fiftyWatermarked720_keepsAtMostEntryAndByteCaps_andEvictsEarlyPaths() = runTest {
        val repo = repository()
        val edge = PreviewResolutionPolicy.BUCKET_720
        val bmp = square(edge)
        val bytesPer = PreviewImageRepository.approxImageBitmapBytes(bmp)
        val entryCap = PreviewImageRepository.DEFAULT_WATERMARKED_ENTRIES_MAX
        val byteCap = PreviewImageRepository.DEFAULT_WATERMARKED_BYTES_MAX
        val effectiveByBytes = (byteCap / bytesPer).toInt()
        assertTrue(
            effectiveByBytes < 50,
            "sanity: 50×${edge}px bitmaps cannot all fit watermarked byte budget",
        )
        assertTrue(
            effectiveByBytes <= entryCap,
            "at ${edge}px, byte cap ($effectiveByBytes) must be ≤ entry cap $entryCap",
        )
        assertTrue(
            effectiveByBytes >= 20,
            "R1 must retain ≥20×${edge}px WM frames by bytes (got $effectiveByBytes)",
        )

        for (i in 0 until 50) {
            repo.putForTests(
                PreviewKey("/tmp/ewm_src_$i", edge, PreviewPurpose.Watermarked),
                bmp,
            )
        }
        val snap = repo.snapshot()
        assertTrue(snap.watermarkedEntries <= entryCap)
        assertTrue(snap.watermarkedBytes <= byteCap)
        assertTrue(snap.watermarkedEntries <= effectiveByBytes)
        assertNull(
            repo.cached(PreviewKey("/tmp/ewm_src_0", edge, PreviewPurpose.Watermarked)),
        )
        assertTrue(
            repo.cached(PreviewKey("/tmp/ewm_src_49", edge, PreviewPurpose.Watermarked)) != null,
        )
        assertNotNull(
            repo.cached(
                PreviewKey(
                    "/tmp/ewm_src_${49 - (effectiveByBytes / 2).coerceAtLeast(1)}",
                    edge,
                    PreviewPurpose.Watermarked,
                ),
            ),
        )
    }

    @Test
    fun neighborPrefetchWindow_atCurrentLongEdge_fitsWithoutThrashingFocus() = runTest {
        val edge = PreviewResolutionPolicy.PHONE_PREVIEW_MAX_LONG_EDGE_PX
        val caps = PreviewWorkingSetBudget.caps(edge, physicalMemoryBytes = 8L shl 30)
        val repo = repository()
        repo.applyWorkingSetCaps(caps)
        val bmp = fourByThree(edge)
        val bytesPer = PreviewImageRepository.approxImageBitmapBytes(bmp)
        val needForFive = bytesPer * 5
        assertTrue(needForFive <= caps.watermarkedBytesMax)
        assertTrue(needForFive <= caps.jointBytesMax)

        val focus = "/tmp/ewm_src_10"
        val neighbors = listOf(8, 9, 11, 12).map { "/tmp/ewm_src_$it" }
        for (p in neighbors) {
            repo.putForTests(PreviewKey(p, edge, PreviewPurpose.Watermarked), bmp)
        }
        repo.putForTests(PreviewKey(focus, edge, PreviewPurpose.Watermarked), bmp)
        val afterWarm = repo.snapshot().cachedKeys
        assertTrue(afterWarm.contains(PreviewKey(focus, edge, PreviewPurpose.Watermarked)))
        for (p in neighbors) {
            assertTrue(afterWarm.contains(PreviewKey(p, edge, PreviewPurpose.Watermarked)))
        }

        repo.putForTests(PreviewKey("/tmp/ewm_src_13", edge, PreviewPurpose.Watermarked), bmp)
        val afterSixth = repo.snapshot().cachedKeys
        assertTrue(
            !afterSixth.contains(
                PreviewKey(neighbors.first(), edge, PreviewPurpose.Watermarked),
            ),
        )
        assertTrue(afterSixth.contains(PreviewKey(focus, edge, PreviewPurpose.Watermarked)))
        assertTrue(
            afterSixth.contains(PreviewKey("/tmp/ewm_src_13", edge, PreviewPurpose.Watermarked)),
        )
        val snap = repo.snapshot()
        assertTrue(snap.watermarkedBytes <= caps.watermarkedBytesMax)
        assertEquals(
            PreviewWorkingSetBudget.WORKING_SET_FRAMES,
            snap.watermarkedEntries,
        )
    }

    @Test
    fun workingSet_holdsFocusAndBothNeighbours_forEveryAspectRatio() = runTest {
        val cases = listOf(
            Triple("4:3", PreviewResolutionPolicy.PHONE_PREVIEW_MAX_LONG_EDGE_PX, 3.0 / 4.0),
            Triple("5:4", PreviewResolutionPolicy.PHONE_PREVIEW_MAX_LONG_EDGE_PX, 4.0 / 5.0),
            Triple("1:1", PreviewResolutionPolicy.BUCKET_1440, 1.0),
        )
        for ((name, edge, shortEdgeRatio) in cases) {
            val caps = PreviewWorkingSetBudget.caps(edge, physicalMemoryBytes = 8L shl 30)
            val repo = repository()
            repo.applyWorkingSetCaps(caps)
            val bmp = ImageBitmap(
                edge,
                kotlin.math.ceil(edge * shortEdgeRatio).toInt(),
                ImageBitmapConfig.Argb8888,
            )
            val focus = 10
            val window = listOf(focus, focus - 1, focus + 1, focus - 2, focus + 2)
            for (i in window) {
                repo.putForTests(
                    PreviewKey("/tmp/ewm_src_$i", edge, PreviewPurpose.Watermarked),
                    bmp,
                )
            }
            val resident = repo.snapshot().cachedKeys
            for (i in window) {
                assertTrue(
                    resident.contains(
                        PreviewKey("/tmp/ewm_src_$i", edge, PreviewPurpose.Watermarked),
                    ),
                    "$name at ${edge}px: focus+±2 must all stay resident, missing frame $i",
                )
            }
            assertEquals(
                PreviewWorkingSetBudget.WORKING_SET_FRAMES,
                repo.snapshot().watermarkedEntries,
                "$name at ${edge}px must hold exactly the working set",
            )
        }
    }

    @Test
    fun jointPressure_dropsWatermarkedBeforeSourceDecode() = runTest {
        val repo = repository(previewBudget = 60_000L)
        val bmp = ImageBitmap(100, 100, ImageBitmapConfig.Argb8888)
        repo.putForTests(PreviewKey("a", 720, PreviewPurpose.SourcePlaceholder), bmp)
        repo.putForTests(PreviewKey("a", 720, PreviewPurpose.Watermarked), bmp)
        val snap = repo.snapshot()
        assertEquals(1, snap.sourcePlaceholderEntries)
        assertEquals(0, snap.watermarkedEntries)
    }

    @Test
    fun fiftyFilmstrip128_fitsEntryCap_butRepoDoesNotServeCoilUiPath() = runTest {
        val repo = repository()
        val edge = 128
        val bmp = square(edge)
        for (i in 0 until 50) {
            repo.putForTests(
                PreviewKey("/tmp/ewm_src_$i", edge, PreviewPurpose.Filmstrip),
                bmp,
            )
        }
        val snap = repo.snapshot()
        assertTrue(snap.filmstripBytes <= PreviewImageRepository.FILMSTRIP_BYTES_MAX)
        assertEquals(
            PreviewImageRepository.DEFAULT_FILMSTRIP_ENTRIES_MAX,
            snap.filmstripEntries,
        )
        assertNull(repo.cached(PreviewKey("/tmp/ewm_src_0", edge, PreviewPurpose.Filmstrip)))
        assertTrue(
            repo.cached(PreviewKey("/tmp/ewm_src_49", edge, PreviewPurpose.Filmstrip)) != null,
        )
    }

    private fun TestScope.repository(
        previewBudget: Long = PreviewImageRepository.SOURCE_AND_PREVIEW_BYTES_MAX,
    ): PreviewImageRepository<ImageBitmap> =
        PreviewImageRepository<ImageBitmap>(
            ownerScope = CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher(testScheduler)),
            approxBytes = { PreviewImageRepository.approxImageBitmapBytes(it) },
            sourceAndPreviewBytesMax = previewBudget,
        )

    private fun square(edge: Int): ImageBitmap =
        ImageBitmap(edge, edge, ImageBitmapConfig.Argb8888)

    private fun fourByThree(longEdge: Int): ImageBitmap {
        val shortEdge = kotlin.math.ceil(longEdge * 3.0 / 4.0).toInt()
        return ImageBitmap(longEdge, shortEdge, ImageBitmapConfig.Argb8888)
    }
}
