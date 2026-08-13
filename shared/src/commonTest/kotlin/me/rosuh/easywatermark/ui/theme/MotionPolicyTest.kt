package me.rosuh.easywatermark.ui.theme

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * I3 — pure MotionPolicy duration / decorative-loop contract.
 */
class MotionPolicyTest {

    @Test
    fun duration_fullKeepsValue() {
        assertEquals(340, motionDurationMs(MotionPolicy.Full, 340))
        assertEquals(0, motionDurationMs(MotionPolicy.Full, 0))
        assertEquals(0, motionDurationMs(MotionPolicy.Full, -10))
    }

    @Test
    fun duration_reducedScales() {
        // 0.4 * 340 = 136
        assertEquals(136, motionDurationMs(MotionPolicy.Reduced, 340))
        assertEquals(100, motionDurationMs(MotionPolicy.Reduced, 250, reducedScale = 0.4f))
        assertEquals(0, motionDurationMs(MotionPolicy.Reduced, 0))
    }

    @Test
    fun duration_offIsZero() {
        assertEquals(0, motionDurationMs(MotionPolicy.Off, 400))
        assertEquals(0, motionDurationMs(MotionPolicy.Off, 2500))
    }

    @Test
    fun decorativeLoop_onlyFull() {
        assertTrue(motionAllowsDecorativeLoop(MotionPolicy.Full))
        assertFalse(motionAllowsDecorativeLoop(MotionPolicy.Reduced))
        assertFalse(motionAllowsDecorativeLoop(MotionPolicy.Off))
    }

    @Test
    fun timedAnimation_notOff() {
        assertTrue(motionAllowsTimedAnimation(MotionPolicy.Full))
        assertTrue(motionAllowsTimedAnimation(MotionPolicy.Reduced))
        assertFalse(motionAllowsTimedAnimation(MotionPolicy.Off))
    }

    @Test
    fun animatorScale_mapping() {
        assertEquals(MotionPolicy.Off, motionPolicyFromAnimatorScale(0f))
        assertEquals(MotionPolicy.Off, motionPolicyFromAnimatorScale(-1f))
        assertEquals(MotionPolicy.Reduced, motionPolicyFromAnimatorScale(0.25f))
        assertEquals(MotionPolicy.Full, motionPolicyFromAnimatorScale(0.5f))
        assertEquals(MotionPolicy.Full, motionPolicyFromAnimatorScale(1f))
    }

    @Test
    fun reduceMotionFlag_andResolve() {
        assertEquals(MotionPolicy.Reduced, motionPolicyFromReduceMotionFlag(true))
        assertEquals(MotionPolicy.Full, motionPolicyFromReduceMotionFlag(false))
        // Stricter wins: Off scale + no flag → Off
        assertEquals(MotionPolicy.Off, resolveMotionPolicy(animatorScale = 0f, prefersReducedMotion = false))
        // Full scale + reduce flag → Reduced
        assertEquals(MotionPolicy.Reduced, resolveMotionPolicy(animatorScale = 1f, prefersReducedMotion = true))
        assertEquals(MotionPolicy.Full, resolveMotionPolicy(animatorScale = 1f, prefersReducedMotion = false))
    }

    @Test
    fun tokenDurations_present() {
        assertTrue(EwmTheme.motion.logoSweepMs > 0)
        assertTrue(EwmTheme.motion.shellMediumMs > 0)
        assertTrue(EwmTheme.motion.shellShortMs > 0)
        assertTrue(EwmTheme.motion.exportWipeMs > 0)
        assertTrue(EwmTheme.motion.contentSizeMs > 0)
        assertTrue(EwmTheme.motion.gallerySelectMs > 0)
        assertTrue(EwmTheme.motion.previewCrossfadeMinMs > 0)
        assertTrue(EwmTheme.motion.previewCrossfadeMaxMs >= EwmTheme.motion.previewCrossfadeMinMs)
        assertTrue(EwmTheme.motion.firstPreviewRevealMs > 0)
        assertTrue(EwmTheme.motion.exportCheckAppearMs > 0)
        // Off maps all to 0
        assertEquals(0, motionDurationMs(MotionPolicy.Off, EwmTheme.motion.exportWipeMs))
    }

    @Test
    fun previewCrossfade_productHardCut_alwaysZero() {
        // Product 2026-08-12: multi-image switch is hard-cut (no fade settle).
        assertEquals(0, previewCrossfadeDurationMs(MotionPolicy.Off, 0f))
        assertEquals(0, previewCrossfadeDurationMs(MotionPolicy.Off, 1f))
        assertEquals(0, previewCrossfadeDurationMs(MotionPolicy.Full, 0f))
        assertEquals(0, previewCrossfadeDurationMs(MotionPolicy.Full, 1f))
        assertEquals(0, previewCrossfadeDurationMs(MotionPolicy.Full, 0.5f))
        assertEquals(0, previewCrossfadeDurationMs(MotionPolicy.Reduced, 1f))
    }
}
