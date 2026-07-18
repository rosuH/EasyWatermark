package me.rosuh.easywatermark.data.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.assertFalse

/**
 * First multiplatform tests (CMP plan C1.8 — commonTest harness foundation). Runs on every
 * `:shared` target (JVM/desktop, Android, iOS). Pins the contracts of the platform-neutral
 * Domain types now living in commonMain. */
class SharedModelTest {

    @Test
    fun imageFormat_storageId_is_backward_compatible_with_legacy_ordinals() {
        // JPEG=0, PNG=1 must match the historical android Bitmap.CompressFormat ordinals so
        // existing DataStore prefs round-trip without migration (plan R6).
        assertEquals(0, ImageFormat.JPEG.storageId)
        assertEquals(1, ImageFormat.PNG.storageId)
    }

    @Test
    fun imageFormat_fromStorageId_round_trips_and_defaults_to_jpeg() {
        assertEquals(ImageFormat.JPEG, ImageFormat.fromStorageId(0))
        assertEquals(ImageFormat.PNG, ImageFormat.fromStorageId(1))
        // unknown / null id falls back to JPEG (matches the legacy `else -> JPEG` read)
        assertEquals(ImageFormat.JPEG, ImageFormat.fromStorageId(null))
        assertEquals(ImageFormat.JPEG, ImageFormat.fromStorageId(99))
    }

    @Test
    fun result_success_and_failure_factories() {
        val ok = Result.success("data", code = "C", message = "m")
        assertTrue(ok.isSuccess())
        assertFalse(ok.isFailure())
        assertEquals("data", ok.data)

        val fail = Result.failure<String>(code = "E")
        assertTrue(fail.isFailure())
        assertFalse(fail.isSuccess())
        assertEquals("E", fail.code)
    }

    @Test
    fun jobState_wraps_result() {
        val ok = Result.success(Unit)
        val success = JobState.Success(ok)
        assertEquals(ok, success.result)
        assertTrue(success.result.isSuccess())

        val failResult = Result.failure<Unit>(code = "X")
        val failure = JobState.Failure(failResult)
        assertEquals("X", failure.result.code)
        assertTrue(failure.result.isFailure())
    }
}
