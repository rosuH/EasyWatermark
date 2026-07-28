package me.rosuh.easywatermark.ui.save

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import me.rosuh.easywatermark.data.model.JobState
import me.rosuh.easywatermark.data.model.Result as EwmResult
import me.rosuh.easywatermark.ui.theme.MotionPolicy

/**
 * Production success-icon decision seam for [ExportProgressOverlay].
 * Exercises the helper the overlay actually calls — not source-string scans.
 */
class ExportProgressOverlayTest {

    @Test
    fun successIconTokens_deliberateNearFinalArrival() {
        assertTrue(ExportSuccessIconMs in 80..400, "icon entrance must be a short deliberate motion")
        // Near-final start scale (not a far pop from ~0.7).
        assertTrue(
            ExportSuccessIconStartScale in 0.92f..0.95f,
            "start scale=${ExportSuccessIconStartScale} must be ~0.92–0.95",
        )
    }

    @Test
    fun exportOverlayPhase_mapsJobState() {
        assertEquals(ExportOverlayPhase.Ready, exportOverlayPhase(JobState.Ready))
        assertEquals(ExportOverlayPhase.Ing, exportOverlayPhase(JobState.Ing))
        assertEquals(
            ExportOverlayPhase.Success,
            exportOverlayPhase(JobState.Success(EwmResult.success(Unit))),
        )
        assertEquals(
            ExportOverlayPhase.Failure,
            exportOverlayPhase(JobState.Failure(EwmResult.failure<Unit>(message = "x"))),
        )
    }

    @Test
    fun resolveSuccessIconMotion_liveIngToSuccess_animatesEntrance() {
        assertEquals(
            SuccessIconMotion.AnimateEntrance,
            resolveSuccessIconMotion(
                previous = ExportOverlayPhase.Ing,
                current = ExportOverlayPhase.Success,
            ),
        )
    }

    @Test
    fun resolveSuccessIconMotion_recycledOrRestoredSuccess_snapsFinal() {
        // Recycle while already Success.
        assertEquals(
            SuccessIconMotion.SnapFinal,
            resolveSuccessIconMotion(
                previous = ExportOverlayPhase.Success,
                current = ExportOverlayPhase.Success,
            ),
        )
        // Restored finished list never saw Ing in this composition.
        assertEquals(
            SuccessIconMotion.SnapFinal,
            resolveSuccessIconMotion(
                previous = ExportOverlayPhase.Ready,
                current = ExportOverlayPhase.Success,
            ),
        )
        assertEquals(
            SuccessIconMotion.SnapFinal,
            resolveSuccessIconMotion(
                previous = ExportOverlayPhase.Failure,
                current = ExportOverlayPhase.Success,
            ),
        )
    }

    @Test
    fun resolveSuccessIconMotion_nonSuccess_hides() {
        assertEquals(
            SuccessIconMotion.Hide,
            resolveSuccessIconMotion(ExportOverlayPhase.Ready, ExportOverlayPhase.Ready),
        )
        assertEquals(
            SuccessIconMotion.Hide,
            resolveSuccessIconMotion(ExportOverlayPhase.Ready, ExportOverlayPhase.Ing),
        )
        assertEquals(
            SuccessIconMotion.Hide,
            resolveSuccessIconMotion(ExportOverlayPhase.Ing, ExportOverlayPhase.Failure),
        )
    }

    @Test
    fun exportSuccessIconDuration_motionPolicy_scalesAndSnaps() {
        assertEquals(
            ExportSuccessIconMs,
            exportSuccessIconDurationMs(MotionPolicy.Full),
        )
        assertEquals(0, exportSuccessIconDurationMs(MotionPolicy.Off))
        val reduced = exportSuccessIconDurationMs(MotionPolicy.Reduced)
        assertTrue(reduced in 1 until ExportSuccessIconMs)
    }
}
