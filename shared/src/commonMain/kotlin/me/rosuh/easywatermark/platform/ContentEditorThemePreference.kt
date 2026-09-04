package me.rosuh.easywatermark.platform

/**
 * Cross-platform **follow current photo** preference for Content editor theme (ADR-0027).
 * Default **on**. Separate from wallpaper [DynamicColorCapability].
 *
 * Hosts persist via platform stores (DataStore / Preferences / NSUserDefaults).
 */
interface ContentEditorThemePreference {
    fun isFollowPhoto(): Boolean
    fun setFollowPhoto(enabled: Boolean)
}

/** In-memory default for tests / hosts that have not wired persistence yet. */
class MemoryContentEditorThemePreference(
    initial: Boolean = true,
) : ContentEditorThemePreference {
    private var value = initial
    override fun isFollowPhoto(): Boolean = value
    override fun setFollowPhoto(enabled: Boolean) {
        value = enabled
    }
}
