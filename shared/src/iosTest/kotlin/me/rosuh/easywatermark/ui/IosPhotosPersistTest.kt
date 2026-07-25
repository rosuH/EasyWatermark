package me.rosuh.easywatermark.ui

import kotlinx.coroutines.runBlocking
import me.rosuh.easywatermark.data.model.ImageInfo
import me.rosuh.easywatermark.data.model.JobState
import me.rosuh.easywatermark.data.model.MediaRef
import me.rosuh.easywatermark.data.model.Result
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * D4 P1–P4: Photos persistence is awaited; saved count only after fake Photos success.
 * Pure Kotlin helper — no real Photos UI.
 */
class IosPhotosPersistTest {

    private fun successItem(path: String): ImageInfo {
        val info = ImageInfo(MediaRef(path))
        val result = Result.success(MediaRef(path))
        info.result = result
        info.jobState = JobState.Success(result)
        return info
    }

    private fun readyItem(path: String): ImageInfo = ImageInfo(MediaRef(path))

    /** P1 — Fake Photos fails → persistedCount 0 despite Session Success. */
    @Test
    fun p1_photosFail_afterRenderSuccess_persistedZero() = runBlocking {
        val path = "/tmp/d4-p1.png"
        val items = listOf(successItem(path))
        val edge = IosPhotosSaveEdge { _, onComplete -> onComplete(false, "denied") }
        val result = persistRenderSuccessesToPhotos(
            images = items,
            loadBytes = { byteArrayOf(1, 2, 3) },
            photosSave = edge,
        )
        assertEquals(1, result.renderSuccessCount)
        assertEquals(0, result.persistedCount)
        assertEquals(1, result.photosFailureMessages.size)
        assertTrue(items[0].jobState is JobState.Success, "Photos fail must not rewrite Session Success")
    }

    /** P2 — Fake Photos succeeds → persisted matches render successes. */
    @Test
    fun p2_photosSuccess_persistedMatchesRenderSuccess() = runBlocking {
        val items = listOf(
            successItem("/tmp/d4-p2-a.png"),
            successItem("/tmp/d4-p2-b.png"),
        )
        var calls = 0
        val edge = IosPhotosSaveEdge { _, onComplete ->
            calls++
            onComplete(true, null)
        }
        val result = persistRenderSuccessesToPhotos(
            images = items,
            loadBytes = { byteArrayOf(9) },
            photosSave = edge,
        )
        assertEquals(2, result.renderSuccessCount)
        assertEquals(2, result.persistedCount)
        assertEquals(2, calls)
        assertTrue(result.photosFailureMessages.isEmpty())
    }

    /**
     * P3 — First Photos OK, second fails → partial count 1; first stays Success.
     */
    @Test
    fun p3_partialPhotosFailure_preservesEarlierSuccessCount() = runBlocking {
        val a = successItem("/tmp/d4-p3-a.png")
        val b = successItem("/tmp/d4-p3-b.png")
        val items = listOf(a, b)
        var call = 0
        val edge = IosPhotosSaveEdge { _, onComplete ->
            call++
            if (call == 1) onComplete(true, null) else onComplete(false, "write failed")
        }
        val result = persistRenderSuccessesToPhotos(
            images = items,
            loadBytes = { byteArrayOf(7) },
            photosSave = edge,
        )
        assertEquals(2, result.renderSuccessCount)
        assertEquals(1, result.persistedCount)
        assertEquals(1, result.photosFailureMessages.size)
        assertTrue(a.jobState is JobState.Success)
        assertTrue(b.jobState is JobState.Success)
        val status = photosPersistStatusLine(batchSize = 2, result = result)
        assertTrue(status.contains("Saved 1/2"), status)
        assertTrue(status.contains("Photos failed") || status.contains("failed"), status)
    }

    /**
     * P4 — Production loop helper never counts without await: missing bytes and Ready items
     * do not inflate persistedCount; only post-await success does.
     */
    @Test
    fun p4_onlyAwaitSuccessIncrements_andReadySkipped() = runBlocking {
        val ok = successItem("/tmp/d4-p4-ok.png")
        val missingBytes = successItem("/tmp/d4-p4-missing.png")
        val notRendered = readyItem("/tmp/d4-p4-ready.png")
        val items = listOf(ok, missingBytes, notRendered)
        var photosCalls = 0
        val edge = IosPhotosSaveEdge { _, onComplete ->
            photosCalls++
            onComplete(true, null)
        }
        val result = persistRenderSuccessesToPhotos(
            images = items,
            loadBytes = { path ->
                if (path.contains("missing")) null else byteArrayOf(1)
            },
            photosSave = edge,
        )
        // Two Success items, one load fails before Photos, one Ready skipped.
        assertEquals(2, result.renderSuccessCount)
        assertEquals(1, result.persistedCount)
        assertEquals(1, photosCalls)
        assertEquals(1, result.photosFailureMessages.size)
    }
}
