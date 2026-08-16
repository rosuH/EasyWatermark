@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package me.rosuh.easywatermark.render

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.ImageBitmapConfig
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Behavioral cache/single-flight proof; no source-text contracts. */
class PreviewImageRepositoryTest {

    @Test
    fun coldVisibleAndPrefetchRequests_shareOneDecode_andFanOut() = runTest {
        val repo = repository()
        val key = PreviewKey("/tmp/a", 160, PreviewPurpose.Filmstrip)
        val gate = CompletableDeferred<Unit>()
        var calls = 0
        val first = async(start = CoroutineStart.UNDISPATCHED) {
            repo.load(key) { calls += 1; gate.await(); bitmap(20, 20) }
        }
        val second = async(start = CoroutineStart.UNDISPATCHED) {
            repo.load(key) { calls += 1; bitmap(20, 20) }
        }

        runCurrent()
        assertEquals(1, calls, "visible + prefetch must share the owner decode")
        gate.complete(Unit)
        runCurrent()
        assertEquals(20, first.await()?.width)
        assertEquals(20, second.await()?.width)
        assertEquals(1, calls)
        assertEquals(1, repo.snapshot().cachedEntries)
    }

    @Test
    fun cancelledWaiter_propagatesCancellation_butLifecycleBoundCompletionServesOtherWaiter() = runTest {
        val repo = repository()
        val key = PreviewKey("/tmp/a", 720, PreviewPurpose.SourcePlaceholder)
        val gate = CompletableDeferred<Unit>()
        val owner = async(start = CoroutineStart.UNDISPATCHED) {
            repo.load(key) { gate.await(); bitmap(20, 20) }
        }
        val survivor = async(start = CoroutineStart.UNDISPATCHED) {
            repo.load(key) { error("must join original decode") }
        }

        runCurrent()
        owner.cancelAndJoin()
        assertTrue(owner.isCancelled, "waiter cancellation must not be suppressed")
        assertFalse(survivor.isCompleted)
        gate.complete(Unit)
        runCurrent()
        assertEquals(20, survivor.await()?.width)
        assertEquals(0, repo.snapshot().inFlightEntries)
    }

    @Test
    fun clearDuringDecode_cannotRepopulateOldCache_andNextRequestCanDecodeFreshly() = runTest {
        val repo = repository()
        val key = PreviewKey("/tmp/a", 720, PreviewPurpose.SourcePlaceholder)
        val gate = CompletableDeferred<Unit>()
        val old = async(start = CoroutineStart.UNDISPATCHED) {
            repo.load(key) { gate.await(); bitmap(20, 20) }
        }
        runCurrent()
        repo.clear()
        gate.complete(Unit)
        old.cancelAndJoin()
        runCurrent()
        assertEquals(0, repo.snapshot().cachedEntries)

        val fresh = repo.load(key) { bitmap(30, 30) }
        assertEquals(30, fresh?.width)
        assertEquals(1, repo.snapshot().cachedEntries)
    }

    @Test
    fun jointBudgets_evictAcrossEveryPreviewPurpose_andKeepFilmstripSeparateAtEightMiB() = runTest {
        val repo = repository(previewBudget = 80_000L, filmstripBudget = 20_000L)
        repo.putForTests(PreviewKey("source", 720, PreviewPurpose.SourcePlaceholder), bitmap(100, 100))
        repo.putForTests(PreviewKey("watermarked", 720, PreviewPurpose.Watermarked), bitmap(100, 100))
        repo.putForTests(PreviewKey("export", 160, PreviewPurpose.ExportThumbnail), bitmap(100, 100))
        val afterThree = repo.snapshot()
        assertTrue(afterThree.previewBytes <= 80_000L)
        assertTrue(afterThree.cachedEntries <= 2)
        assertEquals(
            0,
            afterThree.exportThumbnailEntries,
            "joint overflow must prefer dropping ExportThumbnail before Watermarked",
        )
        assertEquals(1, afterThree.watermarkedEntries)
        assertEquals(1, afterThree.sourcePlaceholderEntries)

        repo.putForTests(PreviewKey("strip-a", 128, PreviewPurpose.Filmstrip), bitmap(60, 60))
        repo.putForTests(PreviewKey("strip-b", 128, PreviewPurpose.Filmstrip), bitmap(60, 60))
        assertTrue(repo.snapshot().filmstripBytes <= 20_000L)
        assertTrue(repo.snapshot().previewBytes <= 80_000L)
    }

    @Test
    fun closeRejectsFutureLoads_andDropsEveryCache() = runTest {
        val repo = repository()
        repo.putForTests(PreviewKey("a", 128, PreviewPurpose.Filmstrip), bitmap(4, 4))
        repo.close()
        assertEquals(0, repo.snapshot().cachedEntries)
        assertNull(repo.load(PreviewKey("b", 128, PreviewPurpose.Filmstrip)) { bitmap(4, 4) })
    }

    @Test
    fun closeFromOwner_uncontended_completesInFlightWaitersOutsideMutex() = runTest {
        val repo = repository()
        val key = PreviewKey("/tmp/close-owner", 128, PreviewPurpose.Filmstrip)
        val gate = CompletableDeferred<Unit>()
        val waiter = async(start = CoroutineStart.UNDISPATCHED) {
            repo.load(key) {
                gate.await()
                bitmap(8, 8)
            }
        }
        runCurrent()
        assertFalse(waiter.isCompleted)
        repo.closeFromOwner()
        runCurrent()
        assertNull(waiter.await(), "close must complete in-flight waiters with null")
        assertTrue(repo.snapshot().closed)
        gate.complete(Unit)
        runCurrent()
        assertEquals(0, repo.snapshot().cachedEntries, "late decode must not repopulate after close")
        assertNull(repo.load(PreviewKey("/tmp/after", 128, PreviewPurpose.Filmstrip)) { bitmap(1, 1) })
    }

    @Test
    fun delayedDecode_cannotPublishStalePixelsUnderNewBucketIdentity() = runTest {
        val repo = repository()
        val path = "/tmp/bucket-race-source"
        val oldKey = PreviewKey(path, 128, PreviewPurpose.Filmstrip)
        val newKey = PreviewKey(path, 192, PreviewPurpose.Filmstrip)
        val releaseDecode = CompletableDeferred<Unit>()
        val decodeStarted = CompletableDeferred<Unit>()
        val oldBitmap = bitmap(32, 32)
        val newBitmap = bitmap(48, 48)

        val staleWaiter = async(start = CoroutineStart.UNDISPATCHED) {
            repo.load(oldKey) {
                decodeStarted.complete(Unit)
                releaseDecode.await()
                oldBitmap
            }
        }
        decodeStarted.await()
        val freshWaiter = async(start = CoroutineStart.UNDISPATCHED) {
            repo.load(newKey) { newBitmap }
        }
        assertEquals(newBitmap, freshWaiter.await(), "new bucket must decode under its own key")
        assertNull(
            repo.cached(oldKey),
            "stale in-flight must not have completed yet",
        )
        assertNull(
            repo.cached(newKey)?.takeIf { it === oldBitmap },
            "old pixels must never appear under new bucket identity",
        )
        releaseDecode.complete(Unit)
        assertEquals(oldBitmap, staleWaiter.await())
        assertEquals(oldBitmap, repo.cached(oldKey))
        assertEquals(newBitmap, repo.cached(newKey))
        assertTrue(
            repo.cached(newKey) !== oldBitmap,
            "stale 128px result must not replace 192px cache entry",
        )
    }

    @Test
    fun closeFromOwner_whenContended_orphanPathStillClosesAndServesWaiters() = runTest {
        val repo = repository()
        val key = PreviewKey("/tmp/contended", 160, PreviewPurpose.SourcePlaceholder)
        val gate = CompletableDeferred<Unit>()
        val waiter = async(start = CoroutineStart.UNDISPATCHED) {
            repo.load(key) {
                gate.await()
                bitmap(16, 16)
            }
        }
        runCurrent()
        assertFalse(waiter.isCompleted)

        val holdStarted = CompletableDeferred<Unit>()
        val releaseHold = CompletableDeferred<Unit>()
        val holder = async(start = CoroutineStart.UNDISPATCHED) {
            repo.withMutexHeldForTests {
                holdStarted.complete(Unit)
                releaseHold.await()
            }
        }
        holdStarted.await()
        repo.closeFromOwner()
        assertFalse(
            repo.snapshotForTestsImmediate().closed,
            "while mutex is held, closed must not flip yet (orphan still waiting)",
        )
        releaseHold.complete(Unit)
        holder.await()
        runCurrent()
        assertTrue(repo.snapshot().closed, "contended close must eventually mark closed")
        assertNull(waiter.await())
        gate.complete(Unit)
        runCurrent()
        assertEquals(0, repo.snapshot().cachedEntries)
    }

    @Test
    fun evictPurposeExcept_keepsFocusSource() = runTest {
        val repo = repository()
        repo.putForTests(PreviewKey("focus", 720, PreviewPurpose.SourcePlaceholder), bitmap(10, 10))
        repo.putForTests(PreviewKey("neighbor", 720, PreviewPurpose.SourcePlaceholder), bitmap(10, 10))
        repo.putForTests(PreviewKey("focus", 720, PreviewPurpose.Watermarked), bitmap(10, 10))
        repo.evictPurposeExcept(PreviewPurpose.SourcePlaceholder, setOf("focus"))
        assertTrue(repo.cached(PreviewKey("focus", 720, PreviewPurpose.SourcePlaceholder)) != null)
        assertNull(repo.cached(PreviewKey("neighbor", 720, PreviewPurpose.SourcePlaceholder)))
        assertTrue(repo.cached(PreviewKey("focus", 720, PreviewPurpose.Watermarked)) != null)
    }

    private fun TestScope.repository(
        previewBudget: Long = PreviewImageRepository.SOURCE_AND_PREVIEW_BYTES_MAX,
        filmstripBudget: Long = PreviewImageRepository.FILMSTRIP_BYTES_MAX,
    ): PreviewImageRepository<ImageBitmap> = PreviewImageRepository<ImageBitmap>(
        ownerScope = CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher(testScheduler)),
        approxBytes = { PreviewImageRepository.approxImageBitmapBytes(it) },
        sourceAndPreviewBytesMax = previewBudget,
        filmstripBytesMax = filmstripBudget,
    )

    private fun bitmap(width: Int, height: Int): ImageBitmap =
        ImageBitmap(width, height, ImageBitmapConfig.Argb8888)
}
