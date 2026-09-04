package me.rosuh.easywatermark.session

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Issue 26 / C4.4R.S1 — pure generation + commit-gate contract mirrored by
 * `iosApp/iosApp/PhotosPickerBatchGate.swift` and [PhotosPickerCommitSerial].
 *
 * Production cancel-on-beginGeneration / FIFO behavior is proven by native Swift unit tests;
 * this suite is an auxiliary decision-table mirror.
 */
class PickerBatchGenerationContractTest {

    private fun beginGeneration(latest: Long): Long = latest + 1

    private fun shouldDeliver(candidate: Long, latest: Long): Boolean = candidate == latest

    private fun shouldBeginCommit(
        candidate: Long,
        latest: Long,
        highestPublished: Long,
    ): Boolean = candidate == latest && candidate > highestPublished

    @Test
    fun g2_finishes_before_g1_only_g2_stages() {
        var latest = 0L
        val g1 = beginGeneration(latest).also { latest = it }
        val g2 = beginGeneration(latest).also { latest = it }
        assertEquals(1L, g1)
        assertEquals(2L, g2)
        assertTrue(shouldDeliver(candidate = g2, latest = latest))
        assertFalse(shouldDeliver(candidate = g1, latest = latest))
    }

    @Test
    fun empty_or_failed_g2_does_not_resurrect_g1() {
        var latest = 0L
        val g1 = beginGeneration(latest).also { latest = it }
        val g2 = beginGeneration(latest).also { latest = it }
        assertEquals(2L, g2)
        assertFalse(shouldDeliver(candidate = g1, latest = latest))
        assertTrue(shouldDeliver(candidate = g2, latest = latest))
        // F9 pure: G1 cannot begin commit after G2 selection advanced latest.
        assertFalse(shouldBeginCommit(g1, latest, highestPublished = 0L))
    }

    @Test
    fun single_batch_stages_once_in_order_identity_preserved() {
        var latest = 0L
        val g = beginGeneration(latest).also { latest = it }
        assertTrue(shouldDeliver(candidate = g, latest = latest))
        assertTrue(shouldDeliver(candidate = g, latest = latest))
        val gNext = beginGeneration(latest).also { latest = it }
        assertFalse(shouldDeliver(candidate = g, latest = latest))
        assertTrue(shouldDeliver(candidate = gNext, latest = latest))
    }

    @Test
    fun g1_check_then_g2_commit_then_g1_commit_only_g2_applied() {
        var latest = 0L
        var highestPublished = 0L
        val applied = mutableListOf<Long>()

        val g1 = beginGeneration(latest).also { latest = it }
        assertTrue(shouldDeliver(candidate = g1, latest = latest))

        val g2 = beginGeneration(latest).also { latest = it }
        assertTrue(shouldBeginCommit(g2, latest, highestPublished))
        highestPublished = g2
        applied += g2

        assertFalse(shouldBeginCommit(g1, latest, highestPublished))
        assertEquals(listOf(g2), applied)
    }

    @Test
    fun serial_commits_g1_then_g2_both_apply_in_order() {
        var latest = 0L
        var highestPublished = 0L
        val applied = mutableListOf<Long>()

        val g1 = beginGeneration(latest).also { latest = it }
        assertTrue(shouldBeginCommit(g1, latest, highestPublished))
        highestPublished = g1
        applied += g1

        val g2 = beginGeneration(latest).also { latest = it }
        assertTrue(shouldBeginCommit(g2, latest, highestPublished))
        highestPublished = g2
        applied += g2

        assertEquals(listOf(1L, 2L), applied)
        assertFalse(shouldBeginCommit(g2, latest, highestPublished))
    }

    @Test
    fun selection_event_order_freezes_generation_independent_of_task_start() {
        var latest = 0L
        val eventG1 = beginGeneration(latest).also { latest = it }
        val eventG2 = beginGeneration(latest).also { latest = it }
        assertTrue(eventG1 < eventG2)
        assertEquals(listOf(2L, 1L), listOf(eventG2, eventG1))
    }
}
