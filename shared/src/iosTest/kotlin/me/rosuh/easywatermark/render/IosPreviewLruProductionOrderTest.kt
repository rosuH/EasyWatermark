package me.rosuh.easywatermark.render

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.ImageBitmapConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.test.runTest
import kotlin.math.ceil
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * S2: the 水印预览缓存 must evict by recency, not by insertion.
 *
 * The pre-existing budget test inserts neighbors **then** focus, an order production never uses.
 * Production inserts focus first (`renderPreviewForCurrentSelection`) and then ±2
 * (`warmNeighborWatermarkedPreviews`), which under FIFO evicts a frame the user is still moving
 * around in and re-decodes it one tap later — 219 ms of ImageIO on a 12MP HEIC (S1 device run).
 */
class IosPreviewLruProductionOrderTest {

    @Test
    fun productionOrder_nextTapEvictsFarFrame_notTheFrameJustViewed() = runTest {
        val edge = PreviewResolutionPolicy.PHONE_PREVIEW_MAX_LONG_EDGE_PX
        val repo = repositoryAtWorkingSetCaps(edge)
        val frame = fourByThree(edge)

        // Tap 12 (cold): focus first, then ±2 — the production insert order.
        repo.putForTests(wm("12", edge), frame)
        for (index in listOf("10", "11", "13", "14")) {
            repo.putForTests(wm(index, edge), frame)
        }

        // Tap 13: focus peek, then the ±2 warm loop peeks each neighbor before deciding to render.
        assertNotNull(repo.cached(wm("13", edge)), "tap 13 must hit the frame warmed by tap 12")
        for (index in listOf("11", "12", "14")) {
            assertNotNull(repo.cached(wm(index, edge)), "neighbor $index must still be resident")
        }
        // 15 is the one genuine miss of tap 13's warm, and it pushes the cache over the cap.
        repo.putForTests(wm("15", edge), frame)

        assertNull(
            repo.cached(wm("10", edge)),
            "the ±3 frame must be the victim once tap 13 warms a 6th frame",
        )
        assertNotNull(
            repo.cached(wm("13", edge)),
            "the frame currently on screen must survive its own ±2 warm",
        )
        assertNotNull(
            repo.cached(wm("12", edge)),
            "the frame just navigated away from must survive — FIFO evicted it here and paid a " +
                "full re-decode on the way back",
        )
    }

    @Test
    fun watermarked_readMakesEntryOutliveAnOlderUnreadEntry() = runTest {
        assertRecencyDecidesVictim(::wm)
    }

    @Test
    fun sourcePlaceholder_readMakesEntryOutliveAnOlderUnreadEntry() = runTest {
        // Source is the expensive purpose (219 ms cold ImageIO vs 9 ms compose), so recency
        // matters here even more than for Watermarked.
        assertRecencyDecidesVictim(::source)
    }

    private suspend fun assertRecencyDecidesVictim(
        key: (String, Int) -> IosPreviewKey,
    ) {
        val edge = PreviewResolutionPolicy.PHONE_PREVIEW_MAX_LONG_EDGE_PX
        val repo = repositoryAtWorkingSetCaps(edge)
        val frame = fourByThree(edge)

        for (index in 0 until WORKING_SET) {
            repo.putForTests(key(index.toString(), edge), frame)
        }
        // Touch the least recently used entry, and only that one.
        assertNotNull(repo.cached(key("0", edge)), "entry 0 must be resident before the touch")

        repo.putForTests(key(WORKING_SET.toString(), edge), frame)

        assertNotNull(repo.cached(key("0", edge)), "a read entry must not be the next victim")
        assertNull(repo.cached(key("1", edge)), "the least recently used entry must be evicted")
    }

    /** Live Host caps for the current 预览长边, where focus + ±2 is exactly the resident set. */
    private suspend fun repositoryAtWorkingSetCaps(edge: Int): IosPreviewImageRepository {
        val repo = IosPreviewImageRepository(
            ownerScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
        )
        repo.applyWorkingSetCaps(
            PreviewWorkingSetBudget.caps(edge, physicalMemoryBytes = 8L shl 30),
        )
        return repo
    }

    private fun wm(index: String, edge: Int) =
        IosPreviewKey("/tmp/ewm_src_$index", edge, IosPreviewPurpose.Watermarked)

    private fun source(index: String, edge: Int) =
        IosPreviewKey("/tmp/ewm_src_$index", edge, IosPreviewPurpose.SourcePlaceholder)

    private fun fourByThree(longEdge: Int): ImageBitmap {
        val shortEdge = ceil(longEdge * 3.0 / 4.0).toInt()
        return ImageBitmap(longEdge, shortEdge, ImageBitmapConfig.Argb8888)
    }

    private companion object {
        const val WORKING_SET = PreviewWorkingSetBudget.WORKING_SET_FRAMES
    }
}
