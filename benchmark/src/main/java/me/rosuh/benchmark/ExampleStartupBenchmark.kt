package me.rosuh.benchmark

import android.app.Application
import android.content.Context
import android.content.Intent
import android.graphics.Point
import android.provider.ContactsContract.Intents.Insert.ACTION
import androidx.benchmark.macro.CompilationMode
import androidx.benchmark.macro.FrameTimingMetric
import androidx.benchmark.macro.StartupMode
import androidx.benchmark.macro.StartupTimingMetric
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import androidx.test.core.app.ApplicationProvider
import androidx.test.core.app.ApplicationProvider.getApplicationContext
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.platform.app.InstrumentationRegistry.getInstrumentation
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Direction
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * This is an example startup benchmark.
 *
 * It navigates to the device's home screen, and launches the default activity.
 *
 * Before running this benchmark:
 * 1) switch your app's active build variant in the Studio (affects Studio runs only)
 * 2) add `<profileable shell=true>` to your app's manifest, within the `<application>` tag
 *
 * Run this benchmark from Studio to see startup measurements, and captured system traces
 * for investigating your app's performance.
 */
@RunWith(AndroidJUnit4::class)
class ExampleStartupBenchmark {
    @get:Rule
    val benchmarkRule = MacrobenchmarkRule()

    private lateinit var device: UiDevice

    private val PACKAGE_NAME = "me.rosuh.easywatermark.debug"

    @Before
    fun setUp() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        device = UiDevice.getInstance(instrumentation)
    }

    @Test
    fun startup() = benchmarkRule.measureRepeated(
        packageName = PACKAGE_NAME,
        metrics = listOf(FrameTimingMetric()),
        iterations = 10,
        setupBlock = {
            val intent = ApplicationProvider.getApplicationContext<Application>().packageManager.getLaunchIntentForPackage(
                PACKAGE_NAME
            )
            intent?.let { startActivityAndWait(it) }
        }
    ) {
        device.findObject(By.text("选择图片")).click()
        // Set gesture margin to avoid triggering gesture nav
        // with input events from automation.

        // Scroll down several times
        device.wait(Until.hasObject(By.res("me.rosuh.easywatermark.debug:id/rv_content")), 5000)
        val recycler = device.findObject(By.res("me.rosuh.easywatermark.debug:id/rv_content"))
        recycler.longClick()
        for (i in 1..10) {
            recycler.scroll(Direction.DOWN, 2f)
            device.waitForIdle()
        }
    }
}