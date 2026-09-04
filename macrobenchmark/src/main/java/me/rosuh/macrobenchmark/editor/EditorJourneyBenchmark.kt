package me.rosuh.macrobenchmark.editor

import androidx.benchmark.macro.BaselineProfileMode
import androidx.benchmark.macro.CompilationMode
import androidx.benchmark.macro.FrameTimingMetric
import androidx.benchmark.macro.StartupMode
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import com.example.benchmark.macro.base.util.DEFAULT_ITERATIONS
import com.example.benchmark.macro.base.util.TARGET_PACKAGE
import me.rosuh.macrobenchmark.journey.ProductJourneys.tryEnterEditorViaShare
import me.rosuh.macrobenchmark.journey.ProductJourneys.tryOpenExportEntry
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * H1 — **non-startup** Macrobenchmark journeys (FrameTimingMetric).
 *
 * Measures editor-open-via-share and export-entry frame timings. Does **not** define
 * H3 latency budgets / CI fail thresholds — numbers are observational only.
 */
@LargeTest
@RunWith(AndroidJUnit4::class)
class EditorJourneyBenchmark {

    @get:Rule
    val benchmarkRule = MacrobenchmarkRule()

    @Test
    fun editorOpenViaShare_partialBaselineProfile() = editorOpen(
        CompilationMode.Partial(baselineProfileMode = BaselineProfileMode.UseIfAvailable),
    )

    @Test
    fun editorOpenViaShare_noCompilation() = editorOpen(CompilationMode.None())

    private fun editorOpen(compilationMode: CompilationMode) = benchmarkRule.measureRepeated(
        packageName = TARGET_PACKAGE,
        metrics = listOf(FrameTimingMetric()),
        iterations = DEFAULT_ITERATIONS.coerceAtMost(5), // keep local runs bounded
        startupMode = StartupMode.WARM,
        compilationMode = compilationMode,
        setupBlock = {
            pressHome()
        },
    ) {
        // measure block: enter editor and open export sheet when possible
        val ok = tryEnterEditorViaShare()
        if (ok) {
            tryOpenExportEntry()
        }
        // Always end idle so frames settle for metrics
        device.waitForIdle()
    }
}
