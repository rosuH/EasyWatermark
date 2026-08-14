package me.rosuh.easywatermark.ui

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import platform.Foundation.NSLock
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * S4: a CLAMP drag must not queue one full render per pointer sample.
 *
 * The guarantee is not "fewer renders on average" but a hard bound: at most one render in flight
 * plus one pending, and the offset that finally paints is the newest one the gesture produced.
 */
class IosDraftRenderConflatorTest {

    private class Recorder {
        private val lock = NSLock()
        private val values = mutableListOf<Int>()

        fun record(value: Int) = withLock { values += value }
        fun size(): Int = withLock { values.size }
        fun snapshot(): List<Int> = withLock { values.toList() }

        private inline fun <T> withLock(block: () -> T): T {
            lock.lock()
            return try {
                block()
            } finally {
                lock.unlock()
            }
        }
    }

    /**
     * Conflation is a real-concurrency property, so these tests must not run on `runTest`'s
     * virtual clock — it would advance past every timeout before the worker thread does any work.
     */
    private fun runRealTimeTest(block: suspend CoroutineScope.() -> Unit) = runTest {
        withContext(Dispatchers.Default) { block() }
    }

    @Test
    fun rapidDraftSamples_collapseToBoundedRenders_andLastOffsetWins() = runRealTimeTest {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val rendered = Recorder()
        val firstRenderStarted = CompletableDeferred<Unit>()
        val releaseFirstRender = CompletableDeferred<Unit>()

        val conflator = IosDraftRenderConflator<Int>(scope) { offset ->
            if (!firstRenderStarted.isCompleted) {
                firstRenderStarted.complete(Unit)
                // Hold the single worker busy while the whole gesture streams in.
                releaseFirstRender.await()
            }
            rendered.record(offset)
        }

        try {
            conflator.submit(0)
            withTimeout(TIMEOUT_MS) { firstRenderStarted.await() }

            // A 60Hz drag's worth of samples, all arriving while the worker is occupied.
            for (sample in 1..GESTURE_SAMPLES) {
                conflator.submit(sample)
            }
            releaseFirstRender.complete(Unit)

            withTimeout(TIMEOUT_MS) {
                while (rendered.size() < 2) yield()
            }

            val counts = conflator.countsForTests()
            assertEquals(
                GESTURE_SAMPLES + 1,
                counts.submitted,
                "every gesture sample should be offered",
            )
            val seen = rendered.snapshot()
            assertTrue(
                seen.size <= 2,
                "conflation must bound renders to in-flight + latest, got ${seen.size}: $seen",
            )
            assertEquals(
                GESTURE_SAMPLES,
                seen.last(),
                "the newest offset must be the one that renders, got $seen",
            )
        } finally {
            conflator.close()
        }
    }

    @Test
    fun sequentialDraftSamples_eachRender_whenWorkerIsFree() = runRealTimeTest {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val rendered = Recorder()
        val conflator = IosDraftRenderConflator<Int>(scope) { rendered.record(it) }
        try {
            for (sample in 1..3) {
                conflator.submit(sample)
                withTimeout(TIMEOUT_MS) {
                    while (rendered.size() < sample) yield()
                }
            }
            // Conflation drops nothing when it is not actually behind.
            assertEquals(listOf(1, 2, 3), rendered.snapshot())
        } finally {
            conflator.close()
        }
    }

    private companion object {
        const val TIMEOUT_MS = 10_000L
        const val GESTURE_SAMPLES = 60
    }
}
