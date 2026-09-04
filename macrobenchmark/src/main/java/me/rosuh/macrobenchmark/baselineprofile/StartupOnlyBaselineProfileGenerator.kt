package me.rosuh.macrobenchmark.baselineprofile

import androidx.benchmark.macro.junit4.BaselineProfileRule
import com.example.benchmark.macro.base.util.TARGET_PACKAGE
import org.junit.Rule
import org.junit.Test

/**
 * S4d-364 / H1: Baseline Profile generator (AndroidX Benchmark 1.5 [BaselineProfileRule.collect]).
 *
 * **Startup-only** journey — kept for a minimal profile collect.
 * Editor-adjacent multi-step profile: [ProductBaselineProfileGenerator].
 * Package: [TARGET_PACKAGE] = `me.rosuh.easywatermark`.
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
