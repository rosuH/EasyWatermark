package me.rosuh.easywatermark.font

/**
 * Visit budget for one-shot directory enumeration. Counts every directory entry
 * (folders, non-fonts, fonts), honors cancel, and records truncation instead of
 * silently stopping (ADR-0035 §4).
 */
class FontScanBudget(
    private val limits: FontImportLimits,
    private val cancelled: () -> Boolean = { false },
) {
    private val collected = ArrayList<FontImportCandidate>()
    var visited: Int = 0
        private set
    var truncated: Boolean = false
        private set
    var truncateReason: String? = null
        private set
    var cancelledOut: Boolean = false
        private set

    fun canContinue(): Boolean = !truncated && !cancelledOut

    /**
     * Record one filesystem/SAF entry. Returns false when the walk must stop.
     */
    fun onVisit(): Boolean {
        if (!canContinue()) return false
        if (cancelled()) {
            cancelledOut = true
            return false
        }
        visited += 1
        if (visited > limits.maxVisits) {
            truncated = true
            truncateReason = "Stopped after ${limits.maxVisits} directory entries"
            return false
        }
        return true
    }

    fun offerCandidate(candidate: FontImportCandidate): Boolean {
        if (!canContinue()) return false
        if (collected.size >= limits.maxCandidates) {
            truncated = true
            truncateReason = "Stopped after ${limits.maxCandidates} candidate files"
            return false
        }
        collected += candidate
        return true
    }

    fun snapshot(): FontEnumerationResult = FontEnumerationResult(
        candidates = collected.toList(),
        truncated = truncated,
        truncateReason = truncateReason,
        cancelled = cancelledOut,
        visited = visited,
    )
}

data class FontEnumerationResult(
    val candidates: List<FontImportCandidate>,
    val truncated: Boolean = false,
    val truncateReason: String? = null,
    val cancelled: Boolean = false,
    val visited: Int = 0,
)
