package me.rosuh.easywatermark.font

/**
 * Presentation sanitizer for import failures. User-visible text must not include
 * original directory paths or SAF/content URIs (ADR-0035 / font-panel UI plan).
 */
object FontImportFailureText {
    const val GENERIC_SOURCE: String = "import"

    private val CONTENT_URI = Regex("content://\\S+", RegexOption.IGNORE_CASE)
    private val FILE_URI = Regex("file://\\S+", RegexOption.IGNORE_CASE)

    fun isUnsafeSource(fileName: String): Boolean {
        val n = fileName.trim()
        if (n.isEmpty()) return true
        val lower = n.lowercase()
        return lower == GENERIC_SOURCE ||
            lower.startsWith("content:") ||
            lower.startsWith("file:") ||
            "://" in n ||
            n.startsWith("/") ||
            lower.contains("documents/tree") ||
            "%3a" in lower
    }

    fun visibleFileName(fileName: String): String =
        if (isUnsafeSource(fileName)) GENERIC_SOURCE else fileName

    fun visibleReason(reason: String): String {
        var s = CONTENT_URI.replace(reason, " ")
        s = FILE_URI.replace(s, " ")
        s = s.replace(Regex("\\s+"), " ").trim(' ', ':', '-', ',')
        return s.ifBlank { "Import failed" }
    }

    fun visibleLine(fileName: String, reason: String, localizedSource: String): String {
        val name = if (isUnsafeSource(fileName)) localizedSource else fileName
        return "$name: ${visibleReason(reason)}"
    }

    fun fromProviderException(errorMessage: String?): FontImportFailure = FontImportFailure(
        fileName = GENERIC_SOURCE,
        reason = visibleReason(errorMessage ?: "Import failed"),
    )
}
