package me.rosuh.easywatermark.session

/**
 * Batch export progress (Phase 1 extract of Android [me.rosuh.easywatermark.ui.SaveExportUiState]).
 *
 * D2: [successCount] / [failureCount] / [processedCount] are explicit. [completedCount] remains
 * success-only for existing UI progress bindings (same value as [successCount]).
 */
data class ExportJobState(
    val isSaving: Boolean = false,
    val isFinished: Boolean = false,
    /** Success-only count (historical UI field). Prefer [successCount]. */
    val completedCount: Int = 0,
    val totalCount: Int = 0,
    val successCount: Int = 0,
    val failureCount: Int = 0,
    /**
     * Items that reached a terminal per-item state this run (success + failure + cancelled-in-flight).
     * Unstarted items after cancel are not processed.
     */
    val processedCount: Int = 0,
)
