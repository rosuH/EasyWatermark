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

class PreviewLruProductionOrderTest {

    @Test
    fun productionOrder_nextTapEvictsFarFrame_notTheFrameJustViewed() = runTest {
        val edge = PreviewResolutionPolicy.PHONE_PREVIEW_MAX_LONG_EDGE_PX
        val repo = repositoryAtWorkingSetCaps(edge)
        val frame = fourByThree(edge)

        repo.putForTests(wm("12", edge), frame)
        for (index in listOf("10", "11", "13", "14")) {
            repo.putForTests(wm(index, edge), frame)
        }

        assertNotNull(repo.cached(wm("13", edge)), "tap 13 must hit the frame warmed by tap 12")
        for (index in listOf("11", "12", "14")) {
            assertNotNull(repo.cached(wm(index, edge)), "neighbor $index must still be resident")
        }
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
            "the frame just navigated away from must survive",
        )
    }

    @Test
    fun watermarked_readMakesEntryOutliveAnOlderUnreadEntry() = runTest {
        assertRecencyDecidesVictim(::wm) { it.watermarkedEntriesMax }
    }

    @Test
    fun sourcePlaceholder_readMakesEntryOutliveAnOlderUnreadEntry() = runTest {
        assertRecencyDecidesVictim(::source) { it.sourceEntriesMax }
    }

    private suspend fun assertRecencyDecidesVictim(
        key: (String, Int) -> PreviewKey,
        capacityOf: (PreviewWorkingSetCaps) -> Int,
    ) {
        val edge = PreviewResolutionPolicy.PHONE_PREVIEW_MAX_LONG_EDGE_PX
        val caps = PreviewWorkingSetBudget.caps(edge, physicalMemoryBytes = 8L shl 30)
        val repo = repositoryAtWorkingSetCaps(edge)
        val frame = fourByThree(edge)
        val capacity = capacityOf(caps)

        for (index in 0 until capacity) {
            repo.putForTests(key(index.toString(), edge), frame)
        }
        assertNotNull(repo.cached(key("0", edge)), "entry 0 must be resident before the touch")

        repo.putForTests(key(capacity.toString(), edge), frame)

        assertNotNull(repo.cached(key("0", edge)), "a read entry must not be the next victim")
        assertNull(repo.cached(key("1", edge)), "the least recently used entry must be evicted")
    }

    private suspend fun repositoryAtWorkingSetCaps(edge: Int): PreviewImageRepository<ImageBitmap> {
        val repo = PreviewImageRepository<ImageBitmap>(
            ownerScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
            approxBytes = { PreviewImageRepository.approxImageBitmapBytes(it) },
        )
        repo.applyWorkingSetCaps(
            PreviewWorkingSetBudget.caps(edge, physicalMemoryBytes = 8L shl 30),
        )
        return repo
    }

    private fun wm(index: String, edge: Int) =
        PreviewKey("/tmp/ewm_src_$index", edge, PreviewPurpose.Watermarked)

    private fun source(index: String, edge: Int) =
        PreviewKey("/tmp/ewm_src_$index", edge, PreviewPurpose.SourcePlaceholder)

    private fun fourByThree(longEdge: Int): ImageBitmap {
        val shortEdge = ceil(longEdge * 3.0 / 4.0).toInt()
        return ImageBitmap(longEdge, shortEdge, ImageBitmapConfig.Argb8888)
    }
}
