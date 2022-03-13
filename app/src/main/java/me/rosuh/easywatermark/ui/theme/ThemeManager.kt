package me.rosuh.easywatermark.ui.theme

import android.content.Context
import android.content.res.Configuration
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import me.rosuh.cmonet.CMonet
import me.rosuh.easywatermark.MyApp
import me.rosuh.easywatermark.R
import me.rosuh.easywatermark.utils.ktx.colorPrimary
import me.rosuh.easywatermark.utils.ktx.isNight
import me.rosuh.easywatermark.utils.ktx.supportNight

object ThemeManager {

    private const val TAG = "ThemeManager"

    private var currentIsDarkTheme = false

    private fun transferStatus(newStatus: Boolean) {
        Log.d(
            TAG,
            "transferStatus: currentIsDarkTheme = $currentIsDarkTheme, isDarkTheme =  $newStatus"
        )
        if (currentIsDarkTheme == newStatus) {
            return
        }
        currentIsDarkTheme = newStatus
        refreshColor()
    }

    private fun refreshColor() {}

    fun onConfigurationChanged(newConfig: Configuration) {
        when (newConfig.uiMode and Configuration.UI_MODE_NIGHT_MASK) {
            Configuration.UI_MODE_NIGHT_NO -> {
                transferStatus(false)
            }
            Configuration.UI_MODE_NIGHT_YES -> {
                transferStatus(true)
            }
            else -> {
                // Other values are invalid
            }
        }
    }


    private fun supportNight(): Boolean {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
    }

    private fun isNight() = currentIsDarkTheme

    private val colorPrimaryLight: Int =
        ContextCompat.getColor(MyApp.instance, R.color.md_theme_light_primary)

    private val colorPrimaryDark: Int =
        ContextCompat.getColor(MyApp.instance, R.color.md_theme_dark_primary)

    private val colorPrimaryDynamicLight: Int =
        ContextCompat.getColor(MyApp.instance, R.color.material_dynamic_primary40)

    private val colorPrimaryDynamicDark: Int =
        ContextCompat.getColor(MyApp.instance, R.color.material_dynamic_primary80)

    val colorPrimary: Int
        get() {
            return when {
                CMonet.isDynamicColorAvailable() && isNight() -> {
                    colorPrimaryDynamicDark
                }
                CMonet.isDynamicColorAvailable() -> {
                    colorPrimaryDynamicLight
                }
                isNight() || !supportNight() -> {
                    colorPrimaryDark
                }
                else -> {
                    colorPrimaryLight
                }
            }
        }
}