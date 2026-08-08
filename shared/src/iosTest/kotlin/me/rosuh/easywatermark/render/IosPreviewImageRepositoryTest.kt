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
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.runCurrent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Behavioral cache/single-flight proof; no source-text contracts. */
class IosPreviewImageRepositoryTest {

    @Test
    fun coldVisibleAndPrefetchRequests_shareOneDecode_andFanOut() = runTest {
        val repo = repository()
        val key = IosPreviewKey("/tmp/a", 160, IosPreviewPurpose.Filmstrip)
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
        val key = IosPreviewKey("/tmp/a", 720, IosPreviewPurpose.SourcePlaceholder)
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
        val key = IosPreviewKey("/tmp/a", 720, IosPreviewPurpose.SourcePlaceholder)
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
        repo.putForTests(IosPreviewKey("source", 720, IosPreviewPurpose.SourcePlaceholder), bitmap(100, 100))
        repo.putForTests(IosPreviewKey("watermarked", 720, IosPreviewPurpose.Watermarked), bitmap(100, 100))
        repo.putForTests(IosPreviewKey("export", 160, IosPreviewPurpose.ExportThumbnail), bitmap(100, 100))
        // Three 40k entries are jointly evicted to <=80k rather than each layer getting 80k.
        assertTrue(repo.snapshot().previewBytes <= 80_000L)
        assertTrue(repo.snapshot().cachedEntries <= 2)

        repo.putForTests(IosPreviewKey("strip-a", 128, IosPreviewPurpose.Filmstrip), bitmap(60, 60))
        repo.putForTests(IosPreviewKey("strip-b", 128, IosPreviewPurpose.Filmstrip), bitmap(60, 60))
        assertTrue(repo.snapshot().filmstripBytes <= 20_000L)
        assertTrue(repo.snapshot().previewBytes <= 80_000L)
    }

    @Test
    fun closeRejectsFutureLoads_andDropsEveryCache() = runTest {
        val repo = repository()
        repo.putForTests(IosPreviewKey("a", 128, IosPreviewPurpose.Filmstrip), bitmap(4, 4))
        repo.close()
        assertEquals(0, repo.snapshot().cachedEntries)
        assertNull(repo.load(IosPreviewKey("b", 128, IosPreviewPurpose.Filmstrip)) { bitmap(4, 4) })
    }

    @Test
    fun closeFromOwner_uncontended_completesInFlightWaitersOutsideMutex() = runTest {
        val repo = repository()
        val key = IosPreviewKey("/tmp/close-owner", 128, IosPreviewPurpose.Filmstrip)
        val gate = CompletableDeferred<Unit>()
        val waiter = async(start = CoroutineStart.UNDISPATCHED) {
            repo.load(key) {
                gate.await()
                bitmap(8, 8)
            }
        }
        runCurrent()
        assertFalse(waiter.isCompleted)
        // Synchronous owner path: closed before Host cancelChildren; waiters complete after unlock.
        repo.closeFromOwner()
        runCurrent()
        assertNull(waiter.await(), "close must complete in-flight waiters with null")
        assertTrue(repo.snapshot().closed)
        gate.complete(Unit)
        runCurrent()
        assertEquals(0, repo.snapshot().cachedEntries, "late decode must not repopulate after close")
        assertNull(repo.load(IosPreviewKey("/tmp/after", 128, IosPreviewPurpose.Filmstrip)) { bitmap(1, 1) })
    }

    @Test
    fun delayedDecode_cannotPublishStalePixelsUnderNewBucketIdentity() = runTest {
        // Production-linked race: start decode under bucket 128, cross to 192 before completion.
        // Stale completion must only land under the original IosPreviewKey — never under the new one.
        val repo = repository()
        val path = "/tmp/bucket-race-source"
        val oldKey = IosPreviewKey(path, 128, IosPreviewPurpose.Filmstrip)
        val newKey = IosPreviewKey(path, 192, IosPreviewPurpose.Filmstrip)
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
        // Measurement crossed bucket boundary while old decode is in flight.
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
        // Complete the delayed old decode — it may only populate oldKey.
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
        val key = IosPreviewKey("/tmp/contended", 160, IosPreviewPurpose.SourcePlaceholder)
        val gate = CompletableDeferred<Unit>()
        val waiter = async(start = CoroutineStart.UNDISPATCHED) {
            repo.load(key) {
                gate.await()
                bitmap(16, 16)
            }
        }
        runCurrent()
        assertFalse(waiter.isCompleted)

        // Hold the mutex so tryLock fails and closeFromOwner must use orphanCloseJob.
        // Never call suspend snapshot() while holding — that would self-deadlock under Unconfined.
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

    private fun TestScope.repository(
        previewBudget: Long = IosPreviewImageRepository.SOURCE_AND_PREVIEW_BYTES_MAX,
        filmstripBudget: Long = IosPreviewImageRepository.FILMSTRIP_BYTES_MAX,
    ): IosPreviewImageRepository = IosPreviewImageRepository(
        ownerScope = CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher(testScheduler)),
        sourceAndPreviewBytesMax = previewBudget,
        filmstripBytesMax = filmstripBudget,
    )

    private fun bitmap(width: Int, height: Int): ImageBitmap =
        ImageBitmap(width, height, ImageBitmapConfig.Argb8888)
}
