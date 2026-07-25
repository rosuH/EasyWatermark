package me.rosuh.easywatermark.ui.save

import me.rosuh.easywatermark.session.ExportErrorCodes
import me.rosuh.easywatermark.session.ExportFailure

/**
 * Pure recovery-state model for the save/export sheet (testable without Compose hosts).
 *
 * D5: Cancel / Retry visibility from job ticks.
 * I0: distinct processed/succeeded/failed, destination/policy slots (host strings),
 * and [ExportFailure] → user-facing EN algorithm (hosts map to Res for product UI).
 */
data class ExportRecoveryUiState(
    val isExporting: Boolean,
    val isFinished: Boolean,
    val successCount: Int,
    val failureCount: Int,
    val processedCount: Int,
    val totalCount: Int,
) {
    val showCancel: Boolean get() = isExporting

    /** Retry only after a finished batch that still has failures (D2 preserves Success). */
    val showRetryFailed: Boolean
        get() = isFinished && failureCount > 0

    val hasAnySuccess: Boolean get() = successCount > 0

    /** All items finished as failures (Retry failed still shown). */
    val isAllFailed: Boolean
        get() = isFinished && successCount == 0 && failureCount > 0

    val isPartial: Boolean
        get() = isFinished && successCount > 0 && failureCount > 0

    val isAllSuccess: Boolean
        get() = isFinished && failureCount == 0 && successCount > 0
}

/**
 * English-format progress lines for unit tests and as the algorithm hosts mirror with
 * `stringResource` format args.
 */
object ExportRecoveryUi {
    fun fromJob(
        isSaving: Boolean,
        isFinished: Boolean,
        successCount: Int,
        failureCount: Int,
        processedCount: Int,
        totalCount: Int,
    ): ExportRecoveryUiState = ExportRecoveryUiState(
        isExporting = isSaving,
        isFinished = isFinished,
        successCount = successCount.coerceAtLeast(0),
        failureCount = failureCount.coerceAtLeast(0),
        processedCount = processedCount.coerceAtLeast(0),
        totalCount = totalCount.coerceAtLeast(0),
    )

    /**
     * Primary list subtitle algorithm. Finished states always keep succeeded/failed distinct
     * (I0); processed is available via [distinctCountsLine] / [contentDescription].
     */
    fun summaryLine(state: ExportRecoveryUiState): String {
        val total = state.totalCount.coerceAtLeast(1)
        return when {
            state.isExporting ->
                "Exporting ${state.processedCount} of $total"
            state.isAllSuccess ->
                "Exported ${state.successCount} of $total"
            state.isPartial ->
                "Exported ${state.successCount} of $total (${state.failureCount} failed)"
            state.isAllFailed ->
                "Export failed (0 of $total, ${state.failureCount} failed)"
            state.isFinished && state.successCount == 0 && state.processedCount < total ->
                "Export cancelled (${state.successCount} of $total saved)"
            else ->
                "${state.successCount}/${total}"
        }
    }

    /**
     * Always-addressable three-count line for finished/partial/exporting (I0).
     * Hosts may show this under the list title or fold into a11y only.
     */
    fun distinctCountsLine(state: ExportRecoveryUiState): String {
        val processed = state.processedCount.coerceAtLeast(state.successCount + state.failureCount)
        return "Processed $processed · Succeeded ${state.successCount} · Failed ${state.failureCount}"
    }

    /**
     * Success location line: how many + where (I0). [destinationLabel] is host-localized.
     */
    fun successWhereLine(successCount: Int, destinationLabel: String): String {
        val dest = destinationLabel.ifBlank { "saved location" }
        val n = successCount.coerceAtLeast(0)
        return if (n == 1) "Saved 1 item to $dest" else "Saved $n items to $dest"
    }

    fun contentDescription(state: ExportRecoveryUiState): String {
        val total = state.totalCount.coerceAtLeast(1)
        val processed = state.processedCount.coerceAtLeast(state.successCount + state.failureCount)
        return if (state.isExporting) {
            "Export progress: $processed of $total processed, " +
                "${state.successCount} succeeded, ${state.failureCount} failed"
        } else {
            "Export finished: $processed processed, ${state.successCount} succeeded, " +
                "${state.failureCount} failed, of $total"
        }
    }

    /**
     * Pure EN user message for a typed [ExportFailure] — **never** returns [ExportFailure.message]
     * or a stack/raw exception string (I0). Hosts map [ExportFailureUserKind] to Res strings.
     */
    fun userFacingFailureMessage(failure: ExportFailure): String =
        userFacingFailureMessage(kindOf(failure))

    fun userFacingFailureMessage(kind: ExportFailureUserKind): String = when (kind) {
        ExportFailureUserKind.SourceDecode ->
            "Could not read the image. Try another photo."
        ExportFailureUserKind.Render ->
            "Could not apply the watermark."
        ExportFailureUserKind.Encode ->
            "Could not encode the image."
        ExportFailureUserKind.Permission ->
            "Permission needed to save. Check system settings."
        ExportFailureUserKind.OutOfMemory ->
            "Not enough memory to export. Try fewer or smaller images."
        ExportFailureUserKind.Io ->
            "Storage problem while saving. Free space and try again."
        ExportFailureUserKind.Persistence ->
            "Could not write to storage. Try again."
        ExportFailureUserKind.Cancelled ->
            "Export cancelled."
        ExportFailureUserKind.Generic ->
            "Save failed. Try again."
    }

    fun kindOf(failure: ExportFailure): ExportFailureUserKind = when (failure) {
        is ExportFailure.SourceDecode -> ExportFailureUserKind.SourceDecode
        is ExportFailure.Render -> ExportFailureUserKind.Render
        is ExportFailure.Encode -> ExportFailureUserKind.Encode
        is ExportFailure.Permission -> ExportFailureUserKind.Permission
        is ExportFailure.Io ->
            if (failure.legacyCode == ExportErrorCodes.SAVE_OOM) {
                ExportFailureUserKind.OutOfMemory
            } else {
                ExportFailureUserKind.Io
            }
        is ExportFailure.Persistence -> ExportFailureUserKind.Persistence
        is ExportFailure.Cancelled -> ExportFailureUserKind.Cancelled
    }

    /**
     * Map optional Throwable from export chrome (catch blocks) to a generic user message.
     * I0: product UI must not show [Throwable.message].
     */
    fun genericFailureMessageForThrowable(): String =
        userFacingFailureMessage(ExportFailureUserKind.Generic)
}

/** Machine-stable kind for host → Res mapping (I0). */
enum class ExportFailureUserKind {
    SourceDecode,
    Render,
    Encode,
    Permission,
    OutOfMemory,
    Io,
    Persistence,
    Cancelled,
    Generic,
}
