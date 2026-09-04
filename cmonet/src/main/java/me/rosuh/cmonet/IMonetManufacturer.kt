package me.rosuh.cmonet

interface IMonetManufacturer {
    /**
     * True when system dynamic colors are available **and** the user follows wallpaper
     * (ADR-0027). Not OEM-gated.
     */
    fun isDynamicColorAvailable(): Boolean

    /** User preference: apply system wallpaper Material You when available. Default true. */
    fun isFollowWallpaper(): Boolean

    fun setFollowWallpaper(enabled: Boolean)

    /** @deprecated Prefer [isFollowWallpaper]. */
    @Deprecated("Use isFollowWallpaper", ReplaceWith("isFollowWallpaper()"))
    fun isForceSupport(): Boolean = isFollowWallpaper()

    /** @deprecated Prefer [setFollowWallpaper]. */
    @Deprecated("Use setFollowWallpaper", ReplaceWith("setFollowWallpaper(supported)"))
    fun setForceSupport(supported: Boolean) = setFollowWallpaper(supported)
}
