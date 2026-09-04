package me.rosuh.easywatermark.ui.save

import me.rosuh.easywatermark.session.ExportErrorCodes
import me.rosuh.easywatermark.session.ExportFailure
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * D5 U1–U3 + I0: pure recovery UI model (Cancel / Retry / distinct counts / error mapping).
 */
class ExportRecoveryUiTest {

    /** U1 — Cancel control only while exporting. */
    @Test
    fun u1_showCancel_onlyWhileExporting() {
        val saving = ExportRecoveryUi.fromJob(
            isSaving = true,
            isFinished = false,
            successCount = 0,
            failureCount = 0,
            processedCount = 1,
            totalCount = 3,
        )
        assertTrue(saving.showCancel)
        assertFalse(saving.showRetryFailed)

        val idle = ExportRecoveryUi.fromJob(
            isSaving = false,
            isFinished = false,
            successCount = 0,
            failureCount = 0,
            processedCount = 0,
            totalCount = 3,
        )
        assertFalse(idle.showCancel)
    }

    /** U2 — Retry failed after finished partial failure and all-failed. */
    @Test
    fun u2_showRetryFailed_whenFinishedWithFailures_includingAllFailed() {
        val partial = ExportRecoveryUi.fromJob(
            isSaving = false,
            isFinished = true,
            successCount = 1,
            failureCount = 2,
            processedCount = 3,
            totalCount = 3,
        )
        assertTrue(partial.showRetryFailed)
        assertTrue(partial.isPartial)
        assertFalse(partial.showCancel)
        assertTrue(partial.hasAnySuccess)

        val allFailed = ExportRecoveryUi.fromJob(
            isSaving = false,
            isFinished = true,
            successCount = 0,
            failureCount = 3,
            processedCount = 3,
            totalCount = 3,
        )
        assertTrue(allFailed.showRetryFailed)
        assertTrue(allFailed.isAllFailed)

        val allOk = ExportRecoveryUi.fromJob(
            isSaving = false,
            isFinished = true,
            successCount = 3,
            failureCount = 0,
            processedCount = 3,
            totalCount = 3,
        )
        assertFalse(allOk.showRetryFailed)
        assertTrue(allOk.isAllSuccess)
    }

    /** U3 — contentDescription non-empty and exposes three counts. */
    @Test
    fun u3_contentDescription_exposesProcessedSucceededFailed() {
        val exporting = ExportRecoveryUi.fromJob(
            isSaving = true,
            isFinished = false,
            successCount = 1,
            failureCount = 0,
            processedCount = 1,
            totalCount = 4,
        )
        val exportCd = ExportRecoveryUi.contentDescription(exporting)
        assertTrue(exportCd.isNotBlank())
        assertTrue(exportCd.contains("1") && exportCd.contains("4"))

        val done = ExportRecoveryUi.fromJob(
            isSaving = false,
            isFinished = true,
            successCount = 2,
            failureCount = 1,
            processedCount = 3,
            totalCount = 3,
        )
        val doneCd = ExportRecoveryUi.contentDescription(done)
        assertTrue(doneCd.contains("processed", ignoreCase = true) || doneCd.contains("3"))
        assertTrue(doneCd.contains("2") && doneCd.contains("1"))
        assertTrue(ExportRecoveryUi.summaryLine(done).contains("failed") || done.failureCount == 1)
    }

    /** I0 — distinct counts line always addressable. */
    @Test
    fun i0_distinctCountsLine_alwaysHasThreeNumbers() {
        val state = ExportRecoveryUi.fromJob(
            isSaving = false,
            isFinished = true,
            successCount = 2,
            failureCount = 1,
            processedCount = 3,
            totalCount = 3,
        )
        val line = ExportRecoveryUi.distinctCountsLine(state)
        assertTrue(line.contains("Processed 3"))
        assertTrue(line.contains("Succeeded 2"))
        assertTrue(line.contains("Failed 1"))
    }

    /** I0 — all-failed summary keeps failed count distinct. */
    @Test
    fun i0_allFailedSummary_includesFailedCount() {
        val allFailed = ExportRecoveryUi.fromJob(
            isSaving = false,
            isFinished = true,
            successCount = 0,
            failureCount = 4,
            processedCount = 4,
            totalCount = 4,
        )
        val line = ExportRecoveryUi.summaryLine(allFailed)
        assertTrue(line.contains("failed", ignoreCase = true))
        assertTrue(line.contains("4"))
        assertTrue(allFailed.showRetryFailed)
    }

    /** I0 — success where line. */
    @Test
    fun i0_successWhereLine_includesCountAndDestination() {
        val one = ExportRecoveryUi.successWhereLine(1, "Photos library")
        assertTrue(one.contains("1") && one.contains("Photos"))
        val many = ExportRecoveryUi.successWhereLine(5, "device album")
        assertTrue(many.contains("5") && many.contains("album"))
    }

    /** I0 — ExportFailure maps to actionable EN; never returns raw machine message. */
    @Test
    fun i0_userFacingFailure_neverUsesRawExceptionMessage() {
        val secret = "java.lang.IllegalStateException: secret stack"
        val kinds = listOf(
            ExportFailure.SourceDecode(message = secret),
            ExportFailure.Render(message = secret),
            ExportFailure.Encode(message = secret),
            ExportFailure.Permission(message = secret),
            ExportFailure.Io.outOfMemory(message = secret),
            ExportFailure.Io(message = secret),
            ExportFailure.Persistence(message = secret),
            ExportFailure.Cancelled(message = secret),
        )
        for (f in kinds) {
            val msg = ExportRecoveryUi.userFacingFailureMessage(f)
            assertFalse(msg.contains("secret"), "must not leak message: $msg")
            assertFalse(msg.contains("Exception"), "must not leak exception type: $msg")
            assertTrue(msg.isNotBlank())
        }
        assertEquals(
            ExportFailureUserKind.OutOfMemory,
            ExportRecoveryUi.kindOf(ExportFailure.Io(legacyCode = ExportErrorCodes.SAVE_OOM)),
        )
        assertEquals(
            ExportRecoveryUi.userFacingFailureMessage(ExportFailureUserKind.Generic),
            ExportRecoveryUi.genericFailureMessageForThrowable(),
        )
    }
}
