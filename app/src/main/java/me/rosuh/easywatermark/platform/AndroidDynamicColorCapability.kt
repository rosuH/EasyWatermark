package me.rosuh.easywatermark.platform

import me.rosuh.cmonet.CMonet

/**
 * Android [DynamicColorCapability] — wallpaper Material You via `:cmonet` (ADR-0027).
 * No OEM allowlist; follow-wallpaper preference (legacy `dynamic_color_force` migration in cmonet).
 */
class AndroidDynamicColorCapability : DynamicColorCapability {
    override fun isAvailable(): Boolean = CMonet.isDynamicColorAvailable()

    override fun isFollowWallpaper(): Boolean = CMonet.isFollowWallpaper()

    override fun setFollowWallpaper(enabled: Boolean) {
        CMonet.setFollowWallpaper(enabled)
    }
}
