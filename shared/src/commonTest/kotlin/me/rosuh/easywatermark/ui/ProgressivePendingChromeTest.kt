package me.rosuh.easywatermark.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ProgressivePendingChromeTest {

    @Test
    fun under120ms_isSilent_evenWithReduceMotion() {
        assertEquals(
            ProgressivePendingChrome.Phase.Silent,
            ProgressivePendingChrome.phase(0, reduceMotion = false),
        )
        assertEquals(
            ProgressivePendingChrome.Phase.Silent,
            ProgressivePendingChrome.phase(119, reduceMotion = true),
        )
    }

    @Test
    fun between120And349_isSilent_withoutReduceMotion() {
        assertEquals(
            ProgressivePendingChrome.Phase.Silent,
            ProgressivePendingChrome.phase(120, reduceMotion = false),
        )
        assertEquals(
            ProgressivePendingChrome.Phase.Silent,
            ProgressivePendingChrome.phase(349, reduceMotion = false),
        )
    }

    @Test
    fun after350ms_isLoading_animation() {
        assertEquals(
            ProgressivePendingChrome.Phase.Loading,
            ProgressivePendingChrome.phase(350, reduceMotion = false),
        )
        assertEquals(
            ProgressivePendingChrome.Phase.Loading,
            ProgressivePendingChrome.phase(5_000, reduceMotion = false),
        )
    }

    @Test
    fun reduceMotion_isStaticLoadingFrom120ms_neverSpinnerWindow() {
        assertEquals(
            ProgressivePendingChrome.Phase.StaticLoading,
            ProgressivePendingChrome.phase(120, reduceMotion = true),
        )
        assertEquals(
            ProgressivePendingChrome.Phase.StaticLoading,
            ProgressivePendingChrome.phase(200, reduceMotion = true),
        )
        assertEquals(
            ProgressivePendingChrome.Phase.StaticLoading,
            ProgressivePendingChrome.phase(5_000, reduceMotion = true),
        )
    }

    @Test
    fun pendingAttempt_deadlineIsStateOwned_notComposableLifetime() {
        // Fresh start stamps attemptStartedAtMs; retry with same importId gets a new attempt clock.
        val t0 = 1_000L
        val started = EditorMediaSlotState.start(listOf("same"), emptyList(), null, nowMs = t0)
        val pending0 = started.slot("same") as EditorMediaSlot.Pending
        assertEquals(t0, pending0.attemptStartedAtMs)
        assertEquals(1L, pending0.attemptId)

        val retried = started
            .markFailed("same", "x")
            .markPendingRetry("same", nowMs = 5_000L)
        val pending1 = retried.slot("same") as EditorMediaSlot.Pending
        assertEquals(5_000L, pending1.attemptStartedAtMs)
        assertTrue(pending1.attemptId > pending0.attemptId)

        // Elapsed for chrome is pure: now - attemptStartedAtMs (composable dispose cannot rewrite state).
        val elapsedAfterRetryAt5100 = 5_100L - pending1.attemptStartedAtMs
        assertEquals(
            ProgressivePendingChrome.Phase.Silent,
            ProgressivePendingChrome.phase(elapsedAfterRetryAt5100, reduceMotion = false),
        )
        val elapsedAfterRetryAt5400 = 5_400L - pending1.attemptStartedAtMs
        assertEquals(
            ProgressivePendingChrome.Phase.Loading,
            ProgressivePendingChrome.phase(elapsedAfterRetryAt5400, reduceMotion = false),
        )
        // Reduced motion: static from 120ms of the *retry* attempt, not the original start.
        assertEquals(
            ProgressivePendingChrome.Phase.StaticLoading,
            ProgressivePendingChrome.phase(120L, reduceMotion = true),
        )
    }
}
