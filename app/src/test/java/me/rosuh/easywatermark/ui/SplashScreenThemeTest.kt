package me.rosuh.easywatermark.ui

import android.app.Application
import android.content.ComponentName
import android.view.ContextThemeWrapper
import android.view.LayoutInflater
import android.widget.FrameLayout
import me.rosuh.easywatermark.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * #424: Android 11 (API 30) crashes when SplashScreen inflates
 * `splash_screen_view` under Theme.MyApp — `?attr/splashScreenIconSize` is
 * undefined unless the launch theme parents Theme.SplashScreen.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], application = Application::class)
class SplashScreenThemeTest {

    @Test
    fun splashScreenView_inflatesWithMainActivityLaunchTheme() {
        val app = RuntimeEnvironment.getApplication()
        val info = app.packageManager.getActivityInfo(
            ComponentName(app, "me.rosuh.easywatermark.ui.MainActivity"),
            0,
        )
        val launchTheme = if (info.theme != 0) info.theme else app.applicationInfo.theme
        assertNotEquals("MainActivity must resolve a launch theme", 0, launchTheme)

        val themed = ContextThemeWrapper(app, launchTheme)
        LayoutInflater.from(themed).inflate(
            androidx.core.splashscreen.R.layout.splash_screen_view,
            FrameLayout(themed),
            false,
        )
    }

    @Test
    fun splashLaunchTheme_resolvesCompatIconSizeAndPostTheme() {
        val app = RuntimeEnvironment.getApplication()
        val info = app.packageManager.getActivityInfo(
            ComponentName(app, "me.rosuh.easywatermark.ui.MainActivity"),
            0,
        )
        val launchTheme = if (info.theme != 0) info.theme else app.applicationInfo.theme
        val themed = ContextThemeWrapper(app, launchTheme)
        val ta = themed.obtainStyledAttributes(
            intArrayOf(
                androidx.core.splashscreen.R.attr.splashScreenIconSize,
                androidx.core.splashscreen.R.attr.postSplashScreenTheme,
                androidx.core.splashscreen.R.attr.windowSplashScreenBackground,
            ),
        )
        try {
            assertTrue(
                "splashScreenIconSize must resolve or splash_screen_view inflation crashes",
                ta.hasValue(0),
            )
            assertEquals(
                "postSplashScreenTheme must hand back Theme.MyApp after installSplashScreen()",
                R.style.Theme_MyApp,
                ta.getResourceId(1, 0),
            )
            assertEquals(
                "windowSplashScreenBackground must stay the olive launch fill",
                R.color.splash_screen_background,
                ta.getResourceId(2, 0),
            )
        } finally {
            ta.recycle()
        }
    }
}
