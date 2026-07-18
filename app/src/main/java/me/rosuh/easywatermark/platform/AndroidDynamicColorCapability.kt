package me.rosuh.easywatermark.platform

import me.rosuh.cmonet.CMonet

/**
 * Android [DynamicColorCapability] — delegates exactly to the existing `:cmonet` [CMonet] object, so
 * The OEM allowlist and the persisted force-support flag (SharedPreferences `sp_water_mark_c_monet`, * key `dynamic_color_force`) are preserved byte-for-byte (Option A). This is a pure indirection:
 * behavior is identical to the prior direct `CMonet.*` call sites.
 */
class AndroidDynamicColorCapability : DynamicColorCapability {
    override fun isAvailable(): Boolean = CMonet.isDynamicColorAvailable()

    override fun isForcedSupport(): Boolean = CMonet.isForceSupportDynamicColor()

    override fun setForcedSupport(enabled: Boolean) {
        if (enabled) {
            CMonet.forceSupportDynamicColor()
        } else {
            CMonet.disableSupportDynamicColor()
        }
    }
}
