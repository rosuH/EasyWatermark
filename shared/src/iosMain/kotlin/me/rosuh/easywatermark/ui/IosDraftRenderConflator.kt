package me.rosuh.easywatermark.ui

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import platform.Foundation.NSLock

/**
 * Backpressure for the CLAMP draft preview: newest offset wins, at most one render in flight.
 *
 * Every pointer move used to launch its own full render. Nothing throttled, conflated, or
 * cancelled, so a drag queued as many decode+compose passes as it had samples and threw away all
 * but the last — `previewGen` only discarded the *result*, after the work had already been paid for.
 *
 * A `Channel.CONFLATED` slot replaces the pending request instead of queueing it, which bounds the
 * pipeline at one running render plus one waiting request regardless of gesture sample rate, and
 * guarantees the render that lands is the most recent offset.
 */
/** J5: internal host helper — not part of the Swift product API surface. */
internal class IosDraftRenderConflator<T>(
    private val scope: CoroutineScope,
    private val render: suspend (T) -> Unit,
) {
    private val requests = Channel<T>(Channel.CONFLATED)
    private var pump: Job? = null

    private val lock = NSLock()
    private var submitted = 0
    private var rendered = 0

    /** Offer the newest draft. Never suspends and never blocks the gesture. */
    fun submit(request: T) {
        lock.lock()
        try {
            submitted += 1
        } finally {
            lock.unlock()
        }
        startPumpIfNeeded()
        requests.trySend(request)
    }

    private fun startPumpIfNeeded() {
        if (pump != null) return
        pump = scope.launch {
            for (request in requests) {
                runCatching { render(request) }
                lock.lock()
                try {
                    rendered += 1
                } finally {
                    lock.unlock()
                }
            }
        }
    }

    fun close() {
        requests.close()
        pump?.cancel()
        pump = null
    }

    /** Draft renders actually executed vs gesture samples offered. Bench/test seam. */
    fun countsForTests(): Counts {
        lock.lock()
        return try {
            Counts(submitted = submitted, rendered = rendered)
        } finally {
            lock.unlock()
        }
    }

    fun resetCountsForTests() {
        lock.lock()
        try {
            submitted = 0
            rendered = 0
        } finally {
            lock.unlock()
        }
    }

    data class Counts(val submitted: Int, val rendered: Int)
}
