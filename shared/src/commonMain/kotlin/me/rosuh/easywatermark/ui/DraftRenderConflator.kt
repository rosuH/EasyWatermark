package me.rosuh.easywatermark.ui

import kotlin.concurrent.Volatile
import kotlin.experimental.ExperimentalObjCRefinement
import kotlin.native.HiddenFromObjC
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch

/**
 * Newest request wins; at most one [render] in flight plus one pending ticket.
 *
 * Used for CLAMP draft **and** slider config paints. [previewGen] only gates *publish*,
 * so this cannot be replaced by generation counters alone.
 */
@OptIn(ExperimentalObjCRefinement::class)
@HiddenFromObjC
open class DraftRenderConflator<T>(
    private val scope: CoroutineScope,
    private val render: suspend (T) -> Unit,
) {
    private val requests = Channel<T>(Channel.CONFLATED)
    private var pump: Job? = null

    @Volatile private var submitted: Int = 0
    @Volatile private var rendered: Int = 0

    /** Offer the newest draft. Never suspends and never blocks the gesture. */
    fun submit(request: T) {
        submitted += 1
        startPumpIfNeeded()
        requests.trySend(request)
    }

    private fun startPumpIfNeeded() {
        if (pump != null) return
        pump = scope.launch {
            for (request in requests) {
                runCatching { render(request) }
                rendered += 1
            }
        }
    }

    fun close() {
        requests.close()
        pump?.cancel()
        pump = null
    }

    /** Draft renders actually executed vs samples offered. Bench/test seam. */
    fun countsForTests(): Counts =
        Counts(submitted = submitted, rendered = rendered)

    fun resetCountsForTests() {
        submitted = 0
        rendered = 0
    }

    data class Counts(val submitted: Int, val rendered: Int)
}
