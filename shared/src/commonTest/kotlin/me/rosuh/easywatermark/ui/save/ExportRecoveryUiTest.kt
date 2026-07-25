package me.rosuh.easywatermark.ui.save

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * D5 U1–U3: pure recovery UI model (Cancel / Retry / non-empty a11y strings).
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

    /** U2 — Retry failed after finished partial failure. */
    @Test
    fun u2_showRetryFailed_whenFinishedWithFailures() {
        val partial = ExportRecoveryUi.fromJob(
            isSaving = false,
            isFinished = true,
            successCount = 1,
            failureCount = 2,
            processedCount = 3,
            totalCount = 3,
        )
        assertTrue(partial.showRetryFailed)
        assertFalse(partial.showCancel)
        assertTrue(partial.hasAnySuccess)

        val allOk = ExportRecoveryUi.fromJob(
            isSaving = false,
            isFinished = true,
            successCount = 3,
            failureCount = 0,
            processedCount = 3,
            totalCount = 3,
        )
        assertFalse(allOk.showRetryFailed)
    }

    /** U3 — contentDescription non-empty for exporting and finished states. */
    @Test
    fun u3_contentDescription_nonEmptyForExportingAndDone() {
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
        assertTrue(exportCd.contains("progress", ignoreCase = true) || exportCd.contains("1"))

        val done = ExportRecoveryUi.fromJob(
            isSaving = false,
            isFinished = true,
            successCount = 2,
            failureCount = 1,
            processedCount = 3,
            totalCount = 3,
        )
        val doneCd = ExportRecoveryUi.contentDescription(done)
        assertTrue(doneCd.isNotBlank())
        assertTrue(doneCd.contains("finished", ignoreCase = true) || doneCd.contains("2"))

        val line = ExportRecoveryUi.summaryLine(done)
        assertTrue(line.contains("2"))
        assertTrue(line.contains("failed") || line.contains("1"))
    }
}
