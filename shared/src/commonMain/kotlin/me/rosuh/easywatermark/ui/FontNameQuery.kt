package me.rosuh.easywatermark.ui

import me.rosuh.easywatermark.font.FontEntry

/** In-memory display-name filter for the font panel. Not persisted. */
object FontNameQuery {
    fun normalized(query: String): String = query.trim()

    fun matches(displayName: String, query: String): Boolean {
        val needle = normalized(query)
        if (needle.isEmpty()) return true
        return displayName.contains(needle, ignoreCase = true)
    }

    fun filter(entries: List<FontEntry>, query: String): List<FontEntry> {
        val needle = normalized(query)
        if (needle.isEmpty()) return entries
        return entries.filter { matches(it.displayName, query) }
    }

    fun isActive(query: String): Boolean = normalized(query).isNotEmpty()
}
