package me.rosuh.easywatermark.platform

import me.rosuh.easywatermark.ui.theme.MotionPolicy
import me.rosuh.easywatermark.ui.theme.resolveMotionPolicy

/**
 * I3 — thin platform edge for reduce-motion / animator scale.
 * Pure mapping lives in [me.rosuh.easywatermark.ui.theme]; this only reads OS flags.
 *
 * Desktop has no reliable OS reduce-motion API → [MotionPolicy.Full] (document residual).
 */
expect fun platformMotionPolicy(): MotionPolicy

/**
 * Test/helpers: resolve without touching OS (desktopTest / pure suites).
 */
fun motionPolicyForTest(
    animatorScale: Float = 1f,
    prefersReducedMotion: Boolean = false,
): MotionPolicy = resolveMotionPolicy(animatorScale, prefersReducedMotion)
