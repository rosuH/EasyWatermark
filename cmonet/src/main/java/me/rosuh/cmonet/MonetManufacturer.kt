package me.rosuh.cmonet

import android.content.Context
import com.google.android.material.color.DynamicColors

/**
 * Wallpaper Material You gate (ADR-0027).
 *
 * Availability = system [DynamicColors.isDynamicColorAvailable] **and** user
 * **follow wallpaper** preference. No OEM manufacturer allowlist.
 *
 * Preference storage: new key [KEY_FOLLOW_WALLPAPER] (default **true**).
 * Legacy [KEY_DYNAMIC_COLOR_FORCE] is **not** mapped to off — historical default
 * was false for "force past OEM", which would incorrectly disable wallpaper MY
 * for every upgrader. Force=true is treated as follow=true once if the new key
 * is absent.
 */
class MonetManufacturer(
    context: Context
) : IMonetManufacturer {
    private val sp: IStorage = SimpleSp(context)

    private var followWallpaperCached: Boolean = readFollowWallpaper(sp)

    override fun isDynamicColorAvailable(): Boolean =
        resolveWallpaperDynamicAvailable(
            systemDynamicAvailable = DynamicColors.isDynamicColorAvailable(),
            followWallpaper = followWallpaperCached,
        )

    override fun isFollowWallpaper(): Boolean = followWallpaperCached

    override fun setFollowWallpaper(enabled: Boolean) {
        sp.save(KEY_FOLLOW_WALLPAPER, enabled)
        followWallpaperCached = enabled
    }

    @Deprecated("Use isFollowWallpaper", ReplaceWith("isFollowWallpaper()"))
    override fun isForceSupport(): Boolean = isFollowWallpaper()

    @Deprecated("Use setFollowWallpaper", ReplaceWith("setFollowWallpaper(supported)"))
    override fun setForceSupport(supported: Boolean) = setFollowWallpaper(supported)

    companion object {
        private const val KEY_FOLLOW_WALLPAPER = "follow_wallpaper"
        /** Legacy About "force past OEM" flag — migration only. */
        private const val KEY_DYNAMIC_COLOR_FORCE = "dynamic_color_force"

        /**
         * Pure availability rule (unit-testable without DynamicColors).
         */
        fun resolveWallpaperDynamicAvailable(
            systemDynamicAvailable: Boolean,
            followWallpaper: Boolean,
        ): Boolean = systemDynamicAvailable && followWallpaper

        private fun readFollowWallpaper(sp: IStorage): Boolean {
            // New key wins when present (including explicit false).
            if (sp.contains(KEY_FOLLOW_WALLPAPER)) {
                return sp.getValue(KEY_FOLLOW_WALLPAPER, true)
            }
            // Legacy force=true → user wanted wallpaper MY on non-allowlisted OEM.
            if (sp.getValue(KEY_DYNAMIC_COLOR_FORCE, false)) {
                sp.save(KEY_FOLLOW_WALLPAPER, true)
                return true
            }
            // Product default: follow wallpaper when system supports it.
            return true
        }
    }
}
