package me.rosuh.easywatermark.session

/**
 * Batch export progress (Phase 1 extract of Android [me.rosuh.easywatermark.ui.SaveExportUiState]).
 * Phase 2 will drive this from the shared ViewModel export loop.
 */
data class ExportJobState(
    val isSaving: Boolean = false,
    val isFinished: Boolean = false,
    val completedCount: Int = 0,
    val totalCount: Int = 0,
)
