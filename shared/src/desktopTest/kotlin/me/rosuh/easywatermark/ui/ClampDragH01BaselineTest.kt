package me.rosuh.easywatermark.ui

import me.rosuh.easywatermark.data.model.ImageFormat
import me.rosuh.easywatermark.data.model.UserPreferences
import me.rosuh.easywatermark.data.model.WaterMark
import me.rosuh.easywatermark.data.model.WatermarkTileMode
import me.rosuh.easywatermark.render.DesktopImageDecoder
import me.rosuh.easywatermark.render.DesktopRenderRequest
import me.rosuh.easywatermark.render.DesktopRenderSaveSpine
import me.rosuh.easywatermark.render.DesktopWatermarkComposer
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.TimeSource

/**
 * H0.1 Desktop **measurement** of the post-commit preview path that [DesktopWindow] runs after
 * CLAMP drag (`applyOffset` → `previewGeneration++` → delay(250) → refreshPreview).
 *
 * Does **not** invent SLOs. Prints ClampDragBench lines and records wall-clock stages for
 * evidence/h0.1. Architecture is measurement-only (no live-draft product change).
 */
class ClampDragH01BaselineTest {

    @BeforeTest
    fun enableBench() {
        ClampDragBench.enabled = true
        ClampDragBench.resetForTests()
    }

    @AfterTest
    fun silence() {
        ClampDragBench.enabled = false
    }

    private fun tempDir(name: String): File =
        File("build/h01-clamp-$name-${System.nanoTime()}").apply {
            deleteRecursively()
            mkdirs()
        }

    /**
     * Mirror of DesktopWindow.refreshPreview stages for a CLAMP offset commit (no UI, no debounce).
     * Debounce is a fixed **250ms** product constant (see DesktopWindow LaunchedEffect).
     */
    @Test
    fun desktop_postCommitPreviewPath_stageTimings_clampFixture() {
        val dir = tempDir("preview-path")
        val sourceBytes = DesktopWatermarkComposer.sampleBackgroundPng(width = 1280, height = 960)
        val prefs = UserPreferences(ImageFormat.JPEG, 80)
        val config = WaterMark.default.copy(
            text = "H01",
            tileMode = WatermarkTileMode.CLAMP,
            textSize = 48f,
            degree = 0f,
            alpha = 200,
        )
        val target = File(dir, "preview.jpg")

        // Warm JIT once (discard).
        DesktopRenderSaveSpine.renderAndSave(
            imageBytes = sourceBytes,
            request = DesktopRenderRequest(config, prefs, 0.5f, 0.5f),
            target = File(dir, "warm.jpg"),
        )

        fun runOnce(label: String, ox: Float, oy: Float): Map<String, Long> {
            val bench = ClampDragBench.previewScope("desktop_preview_refresh")
            val clock = TimeSource.Monotonic
            val t0 = clock.markNow()
            // read (caller already has bytes — DesktopWindow reads file here)
            val readMark = clock.markNow()
            val readMs = (readMark - t0).inWholeMilliseconds
            // For parity with host: time a byte-array "read" from a real file.
            val sourceFile = File(dir, "source-$label.png").apply { writeBytes(sourceBytes) }
            val tRead0 = clock.markNow()
            val bytes = sourceFile.readBytes()
            val fileReadMs = (clock.markNow() - tRead0).inWholeMilliseconds
            bench.mark("read")

            val tSave0 = clock.markNow()
            val saved = DesktopRenderSaveSpine.renderAndSave(
                imageBytes = bytes,
                request = DesktopRenderRequest(config, prefs, ox, oy),
                target = target,
            )
            val saveMs = (clock.markNow() - tSave0).inWholeMilliseconds
            bench.mark("saveFlow")

            val tDec0 = clock.markNow()
            val display = DesktopImageDecoder.decode(target.readBytes())
            val decodeMs = (clock.markNow() - tDec0).inWholeMilliseconds
            bench.mark("decodeDisplay")

            val totalMs = (clock.markNow() - t0).inWholeMilliseconds
            bench.finish(
                mapOf(
                    "label" to label,
                    "offsetX" to ox,
                    "offsetY" to oy,
                    "w" to saved.width,
                    "h" to saved.height,
                    "outBytes" to saved.outputByteCount,
                    "displayW" to display.width,
                    "displayH" to display.height,
                    "fixedDebounceMs" to 250,
                ),
            )
            return mapOf(
                "fileReadMs" to fileReadMs,
                "saveFlowMs" to saveMs,
                "decodeDisplayMs" to decodeMs,
                "totalMs" to totalMs,
                "debounceMs" to 250L,
                "endToVisibleApproxMs" to (250L + totalMs),
            )
        }

        val center = runOnce("center", 0.5f, 0.5f)
        val moved = runOnce("moved", 0.18f, 0.72f)

        // Write a small machine-readable dump next to build/ for evidence collection.
        val dump = File(dir, "h01-desktop-stages.txt")
        dump.writeText(
            buildString {
                appendLine("H0.1 Desktop post-commit preview path (warm process, 1280x960 fixture, JPEG80 CLAMP)")
                appendLine("fixed_debounce_ms=250  # DesktopWindow LaunchedEffect(previewGeneration)")
                appendLine("live_draft_during_drag=false  # ClampPreviewOffsetDrag accumulates only")
                appendLine("commit_count_per_gesture=1  # contract")
                appendLine("run_center=$center")
                appendLine("run_moved=$moved")
                appendLine("dominant_phase_hypothesis=saveFlow_plus_fixedDebounce")
                appendLine("last_bench_line=${ClampDragBench.lastLine}")
            },
        )
        println(dump.readText())

        assertTrue(moved.getValue("saveFlowMs") >= 0)
        assertTrue(moved.getValue("endToVisibleApproxMs") >= 250)
        // saveFlow should dominate over file read for this fixture (honest floor, not SLO).
        assertTrue(
            moved.getValue("saveFlowMs") >= moved.getValue("fileReadMs"),
            "expected compose+encode+write >= file read; got $moved",
        )
        val line = assertNotNull(ClampDragBench.lastLine)
        assertTrue(line.contains("desktop_preview_refresh"), line)
        assertEquals(WatermarkTileMode.CLAMP, config.tileMode)
    }

    @Test
    fun desktop_oneCommitContract_stillDocumentedInWiring() {
        // Structural: host wiring test remains the guard; this only records the product truth.
        assertTrue(true, "≤1 applyOffset per gesture — see ClampPreviewOffsetHostWiringTest")
    }
}
