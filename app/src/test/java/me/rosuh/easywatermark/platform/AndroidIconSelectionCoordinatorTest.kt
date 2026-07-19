package me.rosuh.easywatermark.platform

import android.app.Application
import android.net.Uri
import java.util.ArrayDeque
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import me.rosuh.easywatermark.data.model.MediaRef
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class AndroidIconSelectionCoordinatorTest {

    @Test
    fun success_commitsNewRef_beforeDeletingOldOwnedFile() = runBlocking {
        val old = MediaRef("content://app.fileprovider/watermark_icons/icon-old.png")
        val next = MediaRef("content://app.fileprovider/watermark_icons/icon-next.png")
        val events = mutableListOf<String>()
        val store = RecordingStore(ArrayDeque(listOf(Result.success(next))), events)
        val coordinator = AndroidIconSelectionCoordinator(
            store = store,
            currentIcon = { events += "current"; old },
            commitIcon = { events += "commit:${it.value}" },
        )

        assertEquals(next, coordinator.import(Uri.parse("content://picker/1")).getOrThrow())
        assertEquals(
            listOf(
                "current",
                "prune:${old.value}",
                "copy:content://picker/1",
                "commit:${next.value}",
                "delete:${old.value}",
                "prune:${next.value}",
            ),
            events,
        )
    }

    @Test
    fun copyFailure_keepsOldConfigAndFile_withoutCommit() = runBlocking {
        val failure = IllegalStateException("unreadable")
        val old = MediaRef("content://app.fileprovider/watermark_icons/icon-old.png")
        val events = mutableListOf<String>()
        val store = RecordingStore(ArrayDeque(listOf(Result.failure(failure))), events)
        val coordinator = AndroidIconSelectionCoordinator(
            store = store,
            currentIcon = { events += "current"; old },
            commitIcon = { events += "commit" },
        )

        val result = coordinator.import(Uri.parse("content://picker/missing"))

        assertEquals(failure.message, result.exceptionOrNull()?.message)
        assertEquals(
            listOf("current", "prune:${old.value}", "copy:content://picker/missing"),
            events,
        )
    }

    @Test
    fun commitFailure_deletesNewCopy_butNeverDeletesOldFile() = runBlocking {
        val old = MediaRef("content://app.fileprovider/watermark_icons/icon-old.png")
        val next = MediaRef("content://app.fileprovider/watermark_icons/icon-next.png")
        val failure = IllegalStateException("datastore write failed")
        val events = mutableListOf<String>()
        val store = RecordingStore(ArrayDeque(listOf(Result.success(next))), events)
        val coordinator = AndroidIconSelectionCoordinator(
            store = store,
            currentIcon = { events += "current"; old },
            commitIcon = { events += "commit:${it.value}"; throw failure },
        )

        val result = coordinator.import(Uri.parse("content://picker/1"))

        assertEquals(failure.message, result.exceptionOrNull()?.message)
        assertTrue(events.contains("delete:${next.value}"))
        assertTrue(events.none { it == "delete:${old.value}" })
    }

    @Test
    fun sequentialSelections_finishWithLatestRef_andCleanPreviousOwnedRefs() = runBlocking {
        val original = MediaRef("content://app.fileprovider/watermark_icons/icon-original.png")
        val first = MediaRef("content://app.fileprovider/watermark_icons/icon-first.png")
        val second = MediaRef("content://app.fileprovider/watermark_icons/icon-second.png")
        var current = original
        val events = mutableListOf<String>()
        val store = RecordingStore(
            ArrayDeque(listOf(Result.success(first), Result.success(second))),
            events,
        )
        val coordinator = AndroidIconSelectionCoordinator(
            store = store,
            currentIcon = { current },
            commitIcon = { current = it },
        )

        coordinator.import(Uri.parse("content://picker/1")).getOrThrow()
        coordinator.import(Uri.parse("content://picker/2")).getOrThrow()

        assertEquals(second, current)
        assertTrue(events.contains("delete:${original.value}"))
        assertTrue(events.contains("delete:${first.value}"))
    }

    @Test
    fun concurrentSelections_areSerialized_andSecondSelectionWins() = runBlocking {
        val original = MediaRef("content://app.fileprovider/watermark_icons/icon-original.png")
        val first = MediaRef("content://app.fileprovider/watermark_icons/icon-first.png")
        val second = MediaRef("content://app.fileprovider/watermark_icons/icon-second.png")
        val firstCopyStarted = CompletableDeferred<Unit>()
        val releaseFirstCopy = CompletableDeferred<Unit>()
        var copyCount = 0
        var current = original
        val store = object : DurableIconStore {
            override suspend fun copyToOwnedRef(source: Uri): Result<MediaRef> {
                copyCount += 1
                return if (copyCount == 1) {
                    firstCopyStarted.complete(Unit)
                    releaseFirstCopy.await()
                    Result.success(first)
                } else {
                    Result.success(second)
                }
            }

            override fun deleteIfOwned(ref: MediaRef): Boolean = true
            override fun pruneExcept(keep: MediaRef) = Unit
        }
        val coordinator = AndroidIconSelectionCoordinator(
            store = store,
            currentIcon = { current },
            commitIcon = { current = it },
        )

        val firstResult = async { coordinator.import(Uri.parse("content://picker/1")) }
        firstCopyStarted.await()
        val secondResult = async { coordinator.import(Uri.parse("content://picker/2")) }
        releaseFirstCopy.complete(Unit)

        assertEquals(first, firstResult.await().getOrThrow())
        assertEquals(second, secondResult.await().getOrThrow())
        assertEquals(second, current)
    }

    private class RecordingStore(
        private val copies: ArrayDeque<Result<MediaRef>>,
        private val events: MutableList<String>,
    ) : DurableIconStore {
        override suspend fun copyToOwnedRef(source: Uri): Result<MediaRef> {
            events += "copy:$source"
            return copies.removeFirst()
        }

        override fun deleteIfOwned(ref: MediaRef): Boolean {
            events += "delete:${ref.value}"
            return true
        }

        override fun pruneExcept(keep: MediaRef) {
            events += "prune:${keep.value}"
        }
    }
}
