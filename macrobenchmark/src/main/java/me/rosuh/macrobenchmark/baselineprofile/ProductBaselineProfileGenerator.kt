package me.rosuh.macrobenchmark.baselineprofile

import androidx.benchmark.macro.junit4.BaselineProfileRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import com.example.benchmark.macro.base.util.TARGET_PACKAGE
import me.rosuh.macrobenchmark.journey.ProductJourneys.focusPickControl
import me.rosuh.macrobenchmark.journey.ProductJourneys.openAboutFromLaunch
import me.rosuh.macrobenchmark.journey.ProductJourneys.startLaunchAndWait
import me.rosuh.macrobenchmark.journey.ProductJourneys.tryEnterEditorMultiShare
import me.rosuh.macrobenchmark.journey.ProductJourneys.tryEnterEditorViaShare
import me.rosuh.macrobenchmark.journey.ProductJourneys.tryOpenExportEntry
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * H1 — Baseline Profile generator covering **startup + editor-adjacent** journeys.
 *
 * Ships as one [BaselineProfileRule.collect] multi-step profile block so ART profiles
 * include Launch, About, share-in Editor, optional multi-share filmstrip, and export entry.
 *
 * [StartupOnlyBaselineProfileGenerator] remains for a minimal startup-only collect.
 * Package: [TARGET_PACKAGE] = `me.rosuh.easywatermark` (benchmark/release app id).
 */
@LargeTest
@RunWith(AndroidJUnit4::class)
class ProductBaselineProfileGenerator {

    @get:Rule
    val baselineProfileRule = BaselineProfileRule()

    @Test
    fun startupAndEditorAdjacentJourneys() {
        baselineProfileRule.collect(
            packageName = TARGET_PACKAGE,
            // Include stable user-facing paths only; maxIterations default is fine.
            includeInStartupProfile = false,
        ) {
            // 1) Cold launch → Launch surface
            startLaunchAndWait()

            // 2) About navigation (no gallery)
            openAboutFromLaunch()
            device.pressBack()
            device.waitForIdle()
            // Back may leave About; re-assert Launch for pick control.
            startLaunchAndWait()

            // 3) Representative Launch control (picker open/cancel — not full PHPicker grid)
            focusPickControl()

            // 4) Editor via share-in fixture (primary editor path without PHPicker cells)
            if (tryEnterEditorViaShare()) {
                // 5) Export / save sheet entry from editor chrome
                tryOpenExportEntry()
                device.pressBack()
                device.waitForIdle()
            }

            // 6) Multi-image share when MediaStore allows (filmstrip-adjacent selection)
            tryEnterEditorMultiShare()
            device.waitForIdle()
        }
    }
}
