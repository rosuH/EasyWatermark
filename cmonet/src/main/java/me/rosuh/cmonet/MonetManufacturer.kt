package me.rosuh.cmonet

import android.content.Context
import android.os.Build
import com.google.android.material.color.DynamicColors

class MonetManufacturer(
    context: Context
) : IMonetManufacturer {
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

    private val sp: IStorage = SimpleSp(context)

    /**
     * cache in memory
      */
    private var isForceSupport = sp.getValue(KEY_DYNAMIC_COLOR_FORCE, false)

    override fun isDynamicColorAvailable(): Boolean {
        val setContains =
            supportedSet.contains(Build.MANUFACTURER.lowercase()) || supportedSet.contains(Build.BRAND.lowercase())
        return DynamicColors.isDynamicColorAvailable() && setContains
                || isForceSupport
    }

    override fun setForceSupport(supported: Boolean) {
        sp.save(KEY_DYNAMIC_COLOR_FORCE, supported)
        isForceSupport = supported
    }

    companion object {
        private const val TAG = "MonetManufacturer"
        private const val KEY_DYNAMIC_COLOR_FORCE = "dynamic_color_force"
    }
}