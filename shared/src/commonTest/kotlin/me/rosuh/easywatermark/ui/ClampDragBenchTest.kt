package me.rosuh.easywatermark.ui

import me.rosuh.easywatermark.data.model.WatermarkTileMode
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * H0.1 — instrumentation seam: one commit signal per successful resolve; liveDraft=false;
 * no product state ownership.
 */
class ClampDragBenchTest {

    @BeforeTest
    fun enable() {
        ClampDragBench.enabled = true
        ClampDragBench.resetForTests()
    }

    @AfterTest
    fun silence() {
        ClampDragBench.enabled = false
        ClampDragBench.resetForTests()
    }

    @Test
    fun successfulResolve_recordsExactlyOneCommit_liveDraftWhenNoted() {
        val fitted = computeFittedImageRect(400f, 400f, 200f, 200f)!!
        val commit = resolveClampDragCommit(
            ClampDragGestureSnapshot(
                tileMode = WatermarkTileMode.CLAMP,
                selectionIdAtStart = "a",
                selectionIdAtEnd = "a",
                startInFittedImage = true,
                startOffsetX = 0.5f,
                startOffsetY = 0.5f,
                totalDragX = 20f,
                totalDragY = -10f,
                fitted = fitted,
                cancelled = false,
            ),
        )
        assertNotNull(commit)

        // Simulate adapter end path (without Compose pointer): samples → resolve → host commit.
        val scope = ClampDragBench.gestureScope()
        scope.sample()
        scope.noteLiveDraft() // H0.1-fix: draft emissions during drag
        scope.sample()
        scope.sample()
        scope.mark("drag")
        scope.mark("resolveCommit")
        // Host would call applyOffset here.
        scope.markCommitDone()
        scope.finish(mapOf("platform" to "unit"))

        assertEquals(1, ClampDragBench.lastCommitCount)
        assertEquals(3, ClampDragBench.lastSampleCount)
        assertTrue(ClampDragBench.lastLiveDraft)
        val line = assertNotNull(ClampDragBench.lastLine)
        assertTrue(line.contains("name=gesture"), line)
        assertTrue(line.contains("committed=true"), line)
        assertTrue(line.contains("liveDraft=true"), line)
        assertTrue(line.contains("sampleCount=3"), line)
        assertTrue(line.contains("onOffsetCommit:"), line)
    }

    @Test
    fun cancelledOrNoCommit_recordsZeroCommits() {
        val scope = ClampDragBench.gestureScope()
        scope.sample()
        scope.mark("drag")
        scope.mark("resolveCommit")
        // No markCommitDone — cancelled / resolver null.
        scope.finish(mapOf("cancelled" to true))

        assertEquals(0, ClampDragBench.lastCommitCount)
        val line = assertNotNull(ClampDragBench.lastLine)
        assertTrue(line.contains("committed=false"), line)
        assertTrue(line.contains("liveDraft=false"), line)
        assertFalse(line.contains("committed=true"))
    }

    @Test
    fun previewScope_emitsStageLine() {
        val scope = ClampDragBench.previewScope("desktop_preview_refresh")
        scope.mark("read")
        scope.mark("saveFlow")
        scope.mark("decodeDisplay")
        scope.finish(mapOf("hit" to false))
        val line = assertNotNull(ClampDragBench.lastLine)
        assertTrue(line.contains("name=desktop_preview_refresh"), line)
        assertTrue(line.contains("read:"), line)
        assertTrue(line.contains("saveFlow:"), line)
        assertTrue(line.contains("decodeDisplay:"), line)
    }

    @Test
    fun whenDisabled_noLastLineMutation() {
        ClampDragBench.enabled = true
        val warm = ClampDragBench.previewScope("warm")
        warm.mark("a")
        warm.finish()
        val before = ClampDragBench.lastLine

        ClampDragBench.enabled = false
        val cold = ClampDragBench.previewScope("cold")
        cold.mark("b")
        cold.finish()
        assertEquals(before, ClampDragBench.lastLine)
    }
}
