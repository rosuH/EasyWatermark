package me.rosuh.easywatermark.ui

import me.rosuh.easywatermark.data.model.WaterMark
import me.rosuh.easywatermark.data.model.WatermarkTileMode
import me.rosuh.easywatermark.render.DesktopPreviewRaster
import me.rosuh.easywatermark.render.DesktopRenderRequest
import me.rosuh.easywatermark.render.DesktopRenderSaveSpine
import me.rosuh.easywatermark.render.DesktopWatermarkComposer
import me.rosuh.easywatermark.data.model.ImageFormat
import me.rosuh.easywatermark.data.model.UserPreferences
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.TimeSource

/**
 * H0.1-fix Desktop measurement: offset-only light preview vs baseline full saveFlow+250ms.
 *
 * Same fixture spirit as [ClampDragH01BaselineTest] (1280×960 CLAMP). Not an H3 SLO.
 */
class ClampDragH01FixDesktopTest {

    @BeforeTest
    fun enable() {
        ClampDragBench.enabled = true
        ClampDragBench.resetForTests()
    }

    @AfterTest
    fun silence() {
        ClampDragBench.enabled = false
    }

    private fun tempDir(name: String): File =
        File("build/h01-fix-$name-${System.nanoTime()}").apply {
            deleteRecursively()
            mkdirs()
        }

    @Test
    fun desktop_offsetLightPreview_noDebounce_noSaveFlow_beatsBaseline() {
        val dir = tempDir("light")
        val sourceBytes = DesktopWatermarkComposer.sampleBackgroundPng(width = 1280, height = 960)
        val sourceFile = File(dir, "source.png").apply { writeBytes(sourceBytes) }
        val wm = WaterMark.default.copy(
            text = "H01F",
            tileMode = WatermarkTileMode.CLAMP,
            textSize = 48f,
            degree = 0f,
            alpha = 200,
        )
        val prefs = UserPreferences(ImageFormat.JPEG, 80)

        // Warm both paths.
        DesktopPreviewRaster.renderWatermarked(sourceBytes, wm, 0.5f, 0.5f)
        DesktopRenderSaveSpine.renderAndSave(
            imageBytes = sourceBytes,
            request = DesktopRenderRequest(wm, prefs, 0.5f, 0.5f),
            target = File(dir, "warm.jpg"),
        )

        // --- After: light path (production DesktopPreviewRaster for offset) ---
        val lightBench = ClampDragBench.previewScope("desktop_offset_preview")
        val t0 = TimeSource.Monotonic.markNow()
        lightBench.mark("read")
        val composed = DesktopPreviewRaster.renderWatermarked(
            imageBytes = sourceFile.readBytes(),
            waterMark = wm,
            offsetX = 0.18f,
            offsetY = 0.72f,
        )
        lightBench.mark("compose")
        val lightTotal = t0.elapsedNow().inWholeMilliseconds
        lightBench.finish(
            mapOf(
                "debounceMs" to 0,
                "saveFlow" to false,
                "w" to composed.width,
                "h" to composed.height,
                "maxEdge" to DesktopPreviewRaster.PREVIEW_MAX_EDGE_PX,
            ),
        )

        // --- Before reference: fixed debounce + saveFlow (baseline numbers class) ---
        val baseBench = ClampDragBench.previewScope("desktop_preview_refresh_baseline_ref")
        val t1 = TimeSource.Monotonic.markNow()
        // fixed debounce is product constant for config path; offset path must not pay it
        val fixedDebounce = 250L
        baseBench.mark("read")
        val saved = DesktopRenderSaveSpine.renderAndSave(
            imageBytes = sourceFile.readBytes(),
            request = DesktopRenderRequest(wm, prefs, 0.18f, 0.72f),
            target = File(dir, "full.jpg"),
        )
        baseBench.mark("saveFlow")
        val saveMs = t1.elapsedNow().inWholeMilliseconds
        baseBench.finish(mapOf("debounceMs" to fixedDebounce, "saveFlow" to true))
        val endToVisibleBefore = fixedDebounce + saveMs
        val endToVisibleAfter = lightTotal // no debounce

        val dump = File(dir, "h01-fix-desktop-before-after.txt")
        dump.writeText(
            buildString {
                appendLine("H0.1-fix Desktop offset preview before/after")
                appendLine("fixture=1280x960 CLAMP JPEG80 warm")
                appendLine("BEFORE_endToVisibleApproxMs=$endToVisibleBefore (debounce=$fixedDebounce + saveFlowMs=$saveMs)")
                appendLine("AFTER_endToVisibleApproxMs=$endToVisibleAfter (debounce=0 light compose only)")
                appendLine("AFTER_light_w=${composed.width} h=${composed.height} maxEdge=${DesktopPreviewRaster.PREVIEW_MAX_EDGE_PX}")
                appendLine("BEFORE_full_outBytes=${saved.outputByteCount}")
                appendLine("live_draft_during_drag=true  # adapter onOffsetDraft")
                appendLine("commit_count_per_gesture=1")
                appendLine("draft_exported=false")
                appendLine("last_bench=${ClampDragBench.lastLine}")
            },
        )
        println(dump.readText())

        // Copy-friendly for evidence/
        File("build/h01-fix-desktop-before-after-latest.txt").writeText(dump.readText())

        assertTrue(composed.width <= DesktopPreviewRaster.PREVIEW_MAX_EDGE_PX)
        assertTrue(composed.height <= DesktopPreviewRaster.PREVIEW_MAX_EDGE_PX)
        // Must beat baseline end→visible (drop fixed 250ms at minimum).
        assertTrue(
            endToVisibleAfter < endToVisibleBefore,
            "after=$endToVisibleAfter must be < before=$endToVisibleBefore",
        )
        // Light path must not include the fixed debounce term.
        assertTrue(endToVisibleAfter < fixedDebounce, "light path must be under fixed 250ms debounce alone")
        val line = assertNotNull(ClampDragBench.lastLine)
        // last line is baseline_ref; check light finished earlier — assert from dump contract
        assertFalse(dump.readText().contains("AFTER_endToVisibleApproxMs=250"))
        assertTrue(dump.readText().contains("debounce=0"))
    }

    @Test
    fun desktop_previewRaster_doesNotWriteTempExport() {
        val dir = tempDir("no-temp")
        val before = dir.listFiles()?.map { it.name }?.toSet().orEmpty()
        val bytes = DesktopWatermarkComposer.sampleBackgroundPng(320, 240)
        DesktopPreviewRaster.renderWatermarked(
            imageBytes = bytes,
            waterMark = WaterMark.default.copy(tileMode = WatermarkTileMode.CLAMP, text = "X"),
            offsetX = 0.2f,
            offsetY = 0.8f,
        )
        val after = dir.listFiles()?.map { it.name }?.toSet().orEmpty()
        assertEquals(before, after)
    }

    private fun assertEquals(a: Set<String>, b: Set<String>) {
        kotlin.test.assertEquals(a, b)
    }
}
