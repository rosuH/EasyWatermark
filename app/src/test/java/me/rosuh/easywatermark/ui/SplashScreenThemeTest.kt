package me.rosuh.easywatermark.ui

import android.app.Application
import android.content.ComponentName
import android.view.ContextThemeWrapper
import me.rosuh.easywatermark.R
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * Android launch is Theme.MyApp (2.x). No core-splashscreen inflate.
 * Starting window / API 31+ OS splash fill stays olive `#262611`.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], application = Application::class)
class SplashScreenThemeTest {

    @Test
    fun mainActivity_usesProductTheme() {
        val app = RuntimeEnvironment.getApplication()
        val info = app.packageManager.getActivityInfo(
            ComponentName(app, "me.rosuh.easywatermark.ui.MainActivity"),
            0,
        )
        val launchTheme = if (info.theme != 0) info.theme else app.applicationInfo.theme
        assertEquals(R.style.Theme_MyApp, launchTheme)
    }

    @Test
    fun productTheme_windowBackgroundIsOliveLaunchFill() {
        val app = RuntimeEnvironment.getApplication()
        val themed = ContextThemeWrapper(app, R.style.Theme_MyApp)
        val ta = themed.obtainStyledAttributes(intArrayOf(android.R.attr.windowBackground))
        try {
            assertEquals(
                "windowBackground must stay the olive launch fill",
                R.color.splash_screen_background,
                ta.getResourceId(0, 0),
            )
        } finally {
            ta.recycle()
        }
    }

    @Test
    @Config(sdk = [31])
    fun api31_osSplashBackgroundIsOlive() {
        val app = RuntimeEnvironment.getApplication()
        val themed = ContextThemeWrapper(app, R.style.Theme_MyApp)
        val ta = themed.obtainStyledAttributes(
            intArrayOf(android.R.attr.windowSplashScreenBackground),
        )
        try {
            assertEquals(
                "windowSplashScreenBackground must stay the olive launch fill",
                R.color.splash_screen_background,
                ta.getResourceId(0, 0),
            )
        } finally {
            ta.recycle()
        }
    }
}
