package me.rosuh.easywatermark.ui.save

/**
 * Pure D5 recovery-state model for the save/export sheet (testable without Compose hosts).
 *
 * Hosts map [ExportJobState] / platform flags into this, then drive [SaveExportSheetShell].
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

    fun summaryLine(state: ExportRecoveryUiState): String {
        val total = state.totalCount.coerceAtLeast(1)
        return when {
            state.isExporting ->
                "Exporting ${state.processedCount} of $total"
            state.isFinished && state.failureCount == 0 && state.successCount > 0 ->
                "Exported ${state.successCount} of $total"
            state.isFinished && state.successCount > 0 && state.failureCount > 0 ->
                "Exported ${state.successCount} of $total (${state.failureCount} failed)"
            state.isFinished && state.successCount == 0 && state.failureCount > 0 ->
                "Export failed (0 of $total)"
            state.isFinished && state.successCount == 0 && state.processedCount < total ->
                "Export cancelled (${state.successCount} of $total saved)"
            else ->
                "${state.successCount}/$total"
        }
    }

    fun contentDescription(state: ExportRecoveryUiState): String {
        val total = state.totalCount.coerceAtLeast(1)
        return if (state.isExporting) {
            "Export progress: ${state.processedCount} of $total processed, " +
                "${state.successCount} succeeded, ${state.failureCount} failed"
        } else {
            "Export finished: ${state.successCount} succeeded, " +
                "${state.failureCount} failed, of $total"
        }
    }
}
