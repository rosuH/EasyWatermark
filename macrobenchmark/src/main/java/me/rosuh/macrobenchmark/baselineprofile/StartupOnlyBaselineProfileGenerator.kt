package me.rosuh.macrobenchmark.baselineprofile

import androidx.benchmark.macro.junit4.BaselineProfileRule
import com.example.benchmark.macro.base.util.TARGET_PACKAGE
import org.junit.Rule
import org.junit.Test

/**
 * S4d-364: Baseline Profile generator migrated to AndroidX Benchmark 1.5 API.
 * Uses [BaselineProfileRule.collect] (replaces removed ExperimentalBaselineProfilesApi /
 * collectBaselineProfile). Startup-only journey; TARGET_PACKAGE unchanged.
 */
class StartupOnlyBaselineProfileGenerator {
    @get:Rule
    val baselineProfileRule = BaselineProfileRule()

    @Test
    fun appStartupOnly() {
        baselineProfileRule.collect(packageName = TARGET_PACKAGE) {
            // MacrobenchmarkScope receiver — launches default activity and waits for idle.
            startActivityAndWait()
        }
    }
}
