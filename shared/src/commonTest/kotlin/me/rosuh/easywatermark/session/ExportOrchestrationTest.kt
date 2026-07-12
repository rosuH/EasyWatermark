package me.rosuh.easywatermark.session

import me.rosuh.easywatermark.data.model.ImageInfo
import me.rosuh.easywatermark.data.model.JobState
import me.rosuh.easywatermark.data.model.MediaRef
import me.rosuh.easywatermark.data.model.Result
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Phase 2: export progress accounting used by the session export loop (no DataStore / ViewModel).
 */
class ExportOrchestrationTest {

    @Test
    fun successResult_countsTowardCompleted() {
        val info = ImageInfo(MediaRef("mem://a"))
        val result = Result.success(MediaRef("file://out/a"))
        info.result = result
        info.jobState = JobState.Success(result)
        assertEquals(1, listOf(info).count { it.jobState is JobState.Success })
        assertTrue(result.isSuccess())
    }

    @Test
    fun failureResult_doesNotCountAsSuccess() {
        val info = ImageInfo(MediaRef("mem://b"))
        val result = Result.failure<MediaRef>(null, code = ExportErrorCodes.FILE_NOT_FOUND)
        info.result = result
        info.jobState = JobState.Failure(result)
        assertEquals(0, listOf(info).count { it.jobState is JobState.Success })
        assertEquals(ExportErrorCodes.FILE_NOT_FOUND, result.code)
    }

    @Test
    fun exportJobState_progressShape() {
        val mid = ExportJobState(isSaving = true, completedCount = 1, totalCount = 3)
        assertEquals(1, mid.completedCount)
        assertEquals(3, mid.totalCount)
        val done = ExportJobState(isFinished = true, completedCount = 3, totalCount = 3)
        assertTrue(done.isFinished)
    }
}
