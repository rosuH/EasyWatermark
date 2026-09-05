package me.rosuh.easywatermark.font

/**
 * Canonical path-component containment. A string prefix check would treat
 * `/fonts-other` as inside `/fonts`.
 */
object FontDirectorySafety {
    fun isCanonicalInside(rootCanonical: String, candidateCanonical: String): Boolean {
        val root = trimTrailingSeparators(rootCanonical)
        val candidate = trimTrailingSeparators(candidateCanonical)
        if (root.isEmpty() || candidate.isEmpty()) return false
        if (candidate == root) return true
        val unix = root + "/"
        val win = root + "\\"
        return candidate.startsWith(unix) || candidate.startsWith(win)
    }

    fun trimTrailingSeparators(path: String): String {
        var end = path.length
        while (end > 1) {
            val c = path[end - 1]
            if (c != '/' && c != '\\') break
            end--
        }
        return path.substring(0, end)
    }
}
