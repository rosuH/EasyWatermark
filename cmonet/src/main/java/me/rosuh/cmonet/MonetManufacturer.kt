package me.rosuh.cmonet

import android.os.Build
import com.google.android.material.color.DynamicColors

class MonetManufacturer : IMonetManufacturer {
    private val supportedSet by lazy {
        hashSetOf(
            "samsung",
            "robolectric",
            "google",
            "lge",
            "sony",
            "sharp",
            "hmd global",
            "infinix mobility limited",
            "tecno mobile limited",
            "itel",
            "motorola"
        )
    }

    private var forceSupported = false

    override fun isDynamicColorAvailable(): Boolean {
        val setContains =
            supportedSet.contains(Build.MANUFACTURER.lowercase()) || supportedSet.contains(Build.BRAND.lowercase())
        return DynamicColors.isDynamicColorAvailable() && setContains || forceSupported
    }

    override fun setForceSupport(supported: Boolean) {
        forceSupported = supported
    }
}