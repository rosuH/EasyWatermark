package me.rosuh.easywatermark.font

import okio.Buffer
import okio.Source
import okio.buffer

/**
 * Shared import loop: one-shot directory contents, serial per file, partial success.
 * Reads are bounded; an oversized stream is rejected without retaining the extra payload.
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
            val remaining = limits.maxTotalBytes - totalBytes
            if (remaining <= 0L) {
                truncated = true
                truncateReason = "Import exceeded total size limit"
                failed += FontImportFailure(name, "Total import size limit")
                break
            }
            val declared = candidate.sizeBytes
            if (declared != null && declared > limits.maxFileBytes) {
                failed += FontImportFailure(name, "File exceeds size limit")
                continue
            }
            if (declared != null && declared > remaining) {
                truncated = true
                truncateReason = "Import exceeded total size limit"
                failed += FontImportFailure(name, "Total import size limit")
                break
            }
            val cap = minOf(limits.maxFileBytes, remaining)
            val read = try {
                val source = candidate.openSource()
                try {
                    readBounded(source, cap)
                } finally {
                    source.close()
                }
            } catch (t: Throwable) {
                failed += FontImportFailure(
                    name,
                    t.message?.takeIf { it.isNotBlank() } ?: "Could not read file",
                )
                continue
            }
            when (read) {
                is BoundedRead.TooLarge -> {
                    if (declared != null && declared <= cap) {
                        truncated = true
                        truncateReason = "Import exceeded total size limit"
                    }
                    failed += FontImportFailure(name, "File exceeds size limit")
                    if (read.stoppedAt >= remaining) {
                        truncated = true
                        truncateReason = "Import exceeded total size limit"
                        break
                    }
                    continue
                }
                is BoundedRead.Bytes -> {
                    if (read.bytes.isEmpty()) {
                        failed += FontImportFailure(name, "Empty file")
                        continue
                    }
                    totalBytes += read.bytes.size
                    when (val outcome = store.publishBytes(name, read.bytes, validate)) {
                        is FontPublishOutcome.Added -> added++
                        is FontPublishOutcome.Duplicate -> duplicates++
                        is FontPublishOutcome.Failed ->
                            failed += FontImportFailure(outcome.fileName, outcome.reason)
                    }
                }
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

    /**
     * Import already-enumerated candidates. Scan truncation/cancel is merged into
     * the result so a visit cap is never silent. Candidates found before cancel
     * are still published (partial success).
     */
    fun importEnumerated(
        store: WatermarkFontStore,
        enumeration: FontEnumerationResult,
        limits: FontImportLimits = FontImportLimits.Default,
        validate: (bytes: ByteArray, extension: String) -> ValidatedImportedFont,
        cancelled: () -> Boolean = { false },
    ): FontImportResult {
        val imported = importCandidates(
            store = store,
            candidates = enumeration.candidates,
            limits = limits,
            validate = validate,
            cancelled = if (enumeration.cancelled) ({ false }) else cancelled,
        )
        return imported.copy(
            truncated = imported.truncated || enumeration.truncated,
            truncateReason = imported.truncateReason ?: enumeration.truncateReason,
            cancelled = imported.cancelled || enumeration.cancelled,
        )
    }

    fun readBounded(source: Source, maxBytes: Long): BoundedRead {
        val buffer = Buffer()
        val input = source.buffer()
        try {
            while (!input.exhausted()) {
                val before = buffer.size
                val n = input.read(buffer, CHUNK_BYTES)
                if (n == -1L) break
                if (buffer.size > maxBytes) {
                    val stoppedAt = before + n
                    buffer.clear()
                    return BoundedRead.TooLarge(stoppedAt)
                }
            }
        } finally {
            input.close()
        }
        return BoundedRead.Bytes(buffer.readByteArray())
    }

    private const val CHUNK_BYTES: Long = 8 * 1024L
}

class FontImportCandidate(
    val fileName: String,
    val sizeBytes: Long? = null,
    val openSource: () -> Source,
)

sealed class BoundedRead {
    data class Bytes(val bytes: ByteArray) : BoundedRead()
    data class TooLarge(val stoppedAt: Long) : BoundedRead()
}
