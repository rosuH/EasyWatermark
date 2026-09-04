package me.rosuh.easywatermark.platform

/**
 * Platform gate for **wallpaper** Material You only (ADR-0007, narrowed by ADR-0027).
 *
 * Android implements via `:cmonet`. Content editor theme is a separate path — do not fold it here.
 * Other platforms: unavailable / no-op.
 */
interface DynamicColorCapability {
    /**
     * True when system wallpaper dynamic colors should apply for the current user setting
     * (system available ∧ follow-wallpaper preference).
     */
    fun isAvailable(): Boolean

    /** User preference: follow system wallpaper Material You. Default true on Android. */
    fun isFollowWallpaper(): Boolean

    fun setFollowWallpaper(enabled: Boolean)

    /** @deprecated Prefer [isFollowWallpaper]. */
    @Deprecated("Use isFollowWallpaper", ReplaceWith("isFollowWallpaper()"))
    fun isForcedSupport(): Boolean = isFollowWallpaper()

    /** @deprecated Prefer [setFollowWallpaper]. */
    @Deprecated("Use setFollowWallpaper", ReplaceWith("setFollowWallpaper(enabled)"))
    fun setForcedSupport(enabled: Boolean) = setFollowWallpaper(enabled)
}
