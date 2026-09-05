package me.rosuh.easywatermark.font

/**
 * Shared import loop: one-shot directory contents, serial per file, partial success.
 * Callers supply already-authorized local bytes; native pickers stay on the platform edge.
 */
object WatermarkFontImporter {

    fun importCandidates(
        store: WatermarkFontStore,
        candidates: List<FontImportCandidate>,
        limits: FontImportLimits = FontImportLimits.Default,
        validate: (bytes: ByteArray, extension: String) -> ValidatedImportedFont,
        cancelled: () -> Boolean = { false },
    ): FontImportResult {
        var added = 0
        var duplicates = 0
        val failed = mutableListOf<FontImportFailure>()
        var totalBytes = 0L
        var truncated = false
        var truncateReason: String? = null
        var cancelledOut = false
        val bounded = if (candidates.size > limits.maxCandidates) {
            truncated = true
            truncateReason = "Stopped after ${limits.maxCandidates} candidate files"
            candidates.take(limits.maxCandidates)
        } else {
            candidates
        }
        for (candidate in bounded) {
            if (cancelled()) {
                cancelledOut = true
                break
            }
            val name = candidate.fileName
            if (!WatermarkFontStore.isFontFileName(name)) {
                failed += FontImportFailure(name, "Unsupported file type")
                continue
            }
            val size = candidate.sizeBytes
            if (size != null && size > limits.maxFileBytes) {
                failed += FontImportFailure(name, "File exceeds size limit")
                continue
            }
            if (size != null && totalBytes + size > limits.maxTotalBytes) {
                truncated = true
                truncateReason = "Import exceeded total size limit"
                failed += FontImportFailure(name, "Total import size limit")
                break
            }
            val bytes = try {
                candidate.readBytes()
            } catch (t: Throwable) {
                failed += FontImportFailure(name, t.message?.takeIf { it.isNotBlank() } ?: "Could not read file")
                continue
            }
            if (bytes.size.toLong() > limits.maxFileBytes) {
                failed += FontImportFailure(name, "File exceeds size limit")
                continue
            }
            if (totalBytes + bytes.size > limits.maxTotalBytes) {
                truncated = true
                truncateReason = "Import exceeded total size limit"
                failed += FontImportFailure(name, "Total import size limit")
                break
            }
            totalBytes += bytes.size
            when (val outcome = store.publishBytes(name, bytes, validate)) {
                is FontPublishOutcome.Added -> added++
                is FontPublishOutcome.Duplicate -> duplicates++
                is FontPublishOutcome.Failed -> failed += FontImportFailure(outcome.fileName, outcome.reason)
            }
        }
        return FontImportResult(
            added = added,
            duplicates = duplicates,
            failed = failed,
            truncated = truncated,
            truncateReason = truncateReason,
            cancelled = cancelledOut,
        )
    }
}

class FontImportCandidate(
    val fileName: String,
    val sizeBytes: Long? = null,
    val readBytes: () -> ByteArray,
)
