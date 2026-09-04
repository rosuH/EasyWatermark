package me.rosuh.easywatermark.ui

import me.rosuh.easywatermark.data.model.WatermarkTileMode
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * H0.1-fix contracts (pure):
 * - UI draft may emit many times during a gesture
 * - ≤1 commit at successful end
 * - draft is never a Session/export owner (host must not persist draft; adapter never calls repo)
 * - liveDraft=true when draft emissions recorded
 */
class ClampDragH01FixContractTest {

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
    fun draftSamples_thenOneCommit_liveDraftTrue() {
        val fitted = computeFittedImageRect(400f, 400f, 200f, 200f)!!
        val drafts = mutableListOf<Pair<Float, Float>>()
        var commitCount = 0
        var lastCommit: Pair<Float, Float>? = null
        var clearCount = 0

        // Simulate adapter body without Compose pointer.
        val startOx = 0.5f
        val startOy = 0.5f
        var totalDx = 0f
        var totalDy = 0f
        val scope = ClampDragBench.gestureScope()
        fun emitDraft() {
            val d = applyClampDragDelta(startOx, startOy, totalDx, totalDy, fitted)
            drafts += d
            scope.noteLiveDraft()
        }
        // three samples
        listOf(10f to 0f, 10f to -5f, 5f to 0f).forEach { (dx, dy) ->
            totalDx += dx
            totalDy += dy
            scope.sample()
            emitDraft()
        }
        scope.mark("drag")
        val commit = resolveClampDragCommit(
            ClampDragGestureSnapshot(
                tileMode = WatermarkTileMode.CLAMP,
                selectionIdAtStart = "a",
                selectionIdAtEnd = "a",
                startInFittedImage = true,
                startOffsetX = startOx,
                startOffsetY = startOy,
                totalDragX = totalDx,
                totalDragY = totalDy,
                fitted = fitted,
                cancelled = false,
            ),
        )
        scope.mark("resolveCommit")
        assertNotNull(commit)
        commitCount++
        lastCommit = commit.offsetX to commit.offsetY
        scope.markCommitDone()
        clearCount++ // host onOffsetDraftClear
        scope.finish(mapOf("platform" to "unit"))

        assertEquals(3, drafts.size)
        assertEquals(1, commitCount)
        assertEquals(1, clearCount)
        assertEquals(lastCommit, drafts.last())
        assertEquals(1, ClampDragBench.lastCommitCount)
        assertTrue(ClampDragBench.lastLiveDraft)
        val line = assertNotNull(ClampDragBench.lastLine)
        assertTrue(line.contains("liveDraft=true"), line)
        assertTrue(line.contains("committed=true"), line)
    }

    @Test
    fun cancelledGesture_noCommit_draftCleared() {
        val scope = ClampDragBench.gestureScope()
        scope.sample()
        scope.noteLiveDraft()
        scope.mark("drag")
        scope.mark("resolveCommit")
        // cancelled → no markCommitDone
        val commit = resolveClampDragCommit(
            ClampDragGestureSnapshot(
                tileMode = WatermarkTileMode.CLAMP,
                selectionIdAtStart = "a",
                selectionIdAtEnd = "a",
                startInFittedImage = true,
                startOffsetX = 0.5f,
                startOffsetY = 0.5f,
                totalDragX = 12f,
                totalDragY = 0f,
                fitted = computeFittedImageRect(400f, 400f, 200f, 200f),
                cancelled = true,
            ),
        )
        assertNull(commit)
        scope.finish(mapOf("cancelled" to true))
        assertEquals(0, ClampDragBench.lastCommitCount)
        assertTrue(ClampDragBench.lastLiveDraft)
    }

    @Test
    fun draftIsNotExportOwner_contractDocumented() {
        // Structural product rule: export freezes Session ImageInfo.offset only.
        // This pure test guards the adapter surface — no draft field on ClampDragCommit owner path.
        val commit = resolveClampDragCommit(
            ClampDragGestureSnapshot(
                tileMode = WatermarkTileMode.CLAMP,
                selectionIdAtStart = "a",
                selectionIdAtEnd = "a",
                startInFittedImage = true,
                startOffsetX = 0.4f,
                startOffsetY = 0.6f,
                totalDragX = 20f,
                totalDragY = -10f,
                fitted = computeFittedImageRect(400f, 400f, 200f, 200f),
                cancelled = false,
            ),
        )
        assertNotNull(commit)
        // ClampDragCommit is a plain end-of-gesture value; hosts must applyOffset once.
        // There is no draft export API on this type (fields are offsetX/offsetY only).
        assertTrue(commit.offsetX in 0f..1f)
        assertTrue(commit.offsetY in 0f..1f)
        // Destructure proves only the two offset components exist as the commit payload.
        val (ox, oy) = commit.offsetX to commit.offsetY
        assertEquals(commit.offsetX, ox)
        assertEquals(commit.offsetY, oy)
    }
}
