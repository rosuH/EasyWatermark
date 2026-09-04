package me.rosuh.easywatermark.session

import me.rosuh.easywatermark.data.model.ExportedMedia
import me.rosuh.easywatermark.data.model.ImageFormat
import me.rosuh.easywatermark.data.model.MediaRef
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * D1 pure type contracts (no DataStore / ViewModel).
 * Session production-seam T1 lives in desktopTest [TypedExportSessionTest].
 */
class TypedExportOutcomeTest {

    @Test
    fun exportFailure_taxonomy_isDistinguishableByType() {
        val decode = ExportFailure.SourceDecode(message = "missing")
        val encode = ExportFailure.Encode(message = "compress false")
        val cancelled = ExportFailure.Cancelled(message = "user cancel")
        val persistence = ExportFailure.Persistence(message = "write failed")
        val render = ExportFailure.Render(message = "compose")
        val permission = ExportFailure.Permission(message = "denied")
        val io = ExportFailure.Io(message = "disk full")
        val oom = ExportFailure.Io.outOfMemory("oom")

        assertIs<ExportFailure.SourceDecode>(decode)
        assertIs<ExportFailure.Encode>(encode)
        assertIs<ExportFailure.Cancelled>(cancelled)
        assertIs<ExportFailure.Persistence>(persistence)
        assertIs<ExportFailure.Render>(render)
        assertIs<ExportFailure.Permission>(permission)
        assertIs<ExportFailure.Io>(io)
        assertIs<ExportFailure.Io>(oom)

        assertEquals(ExportErrorCodes.FILE_NOT_FOUND, decode.legacyCode)
        assertEquals(ExportErrorCodes.ENCODE, encode.legacyCode)
        assertEquals(ExportErrorCodes.CANCELLED, cancelled.legacyCode)
        assertEquals(ExportErrorCodes.PERSISTENCE, persistence.legacyCode)
        assertEquals(ExportErrorCodes.RENDER, render.legacyCode)
        assertEquals(ExportErrorCodes.PERMISSION, permission.legacyCode)
        assertEquals(ExportErrorCodes.IO, io.legacyCode)
        assertEquals(ExportErrorCodes.SAVE_OOM, oom.legacyCode)
    }

    @Test
    fun exportOutcome_toLegacyResult_preservesRefAndCodes() {
        val media = ExportedMedia(
            ref = MediaRef("file:///out.jpg"),
            width = 100,
            height = 80,
            format = ImageFormat.JPEG,
            byteCount = 1234L,
        )
        val ok = ExportOutcome.success(media).toLegacyResult()
        assertTrue(ok.isSuccess())
        assertEquals("file:///out.jpg", ok.data?.value)

        val fail = ExportOutcome.failure(
            ExportFailure.SourceDecode(message = "gone"),
        ).toLegacyResult()
        assertTrue(fail.isFailure())
        assertEquals(ExportErrorCodes.FILE_NOT_FOUND, fail.code)
        assertEquals("gone", fail.message)
    }

    @Test
    fun exportedMedia_holdsCompleteSuccessFacts() {
        val media = ExportedMedia(
            ref = MediaRef("content://media/42"),
            width = 2048,
            height = 1536,
            format = ImageFormat.PNG,
            byteCount = 99_001L,
        )
        assertEquals(2048, media.width)
        assertEquals(1536, media.height)
        assertEquals(ImageFormat.PNG, media.format)
        assertEquals(99_001L, media.byteCount)
        assertEquals("content://media/42", media.ref.value)
    }
}
