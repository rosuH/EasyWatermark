package me.rosuh.easywatermark.session

import platform.Foundation.NSLock
import platform.posix.CLOCK_MONOTONIC
import platform.posix.clock_gettime
import platform.posix.timespec
import kotlinx.cinterop.alloc
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr

/**
 * Test-visible progressive-import timeline.
 *
 * Privacy: only event name + generation + opaque importId token (never paths or bytes).
 * Clock: monotonic via CLOCK_MONOTONIC. Events are bounded and reset per generation.
 */
@OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)
internal object ImportTimelineProbe {
    data class Event(
        val name: String,
        val generation: Long,
        val importId: String,
        val monoMs: Long,
    )

    private const val MAX_EVENTS = 256

    private val lock = NSLock()
    private val events = ArrayDeque<Event>(MAX_EVENTS)
    private var t0Ns = 0L
    private var activeGeneration: Long = -1L

    fun reset(generation: Long = -1L) {
        lock.lock()
        try {
            events.clear()
            t0Ns = monoNs()
            activeGeneration = generation
        } finally {
            lock.unlock()
        }
    }

    fun mark(name: String, generation: Long, importId: String = "") {
        val nowNs = monoNs()
        lock.lock()
        try {
            if (t0Ns == 0L || generation != activeGeneration) {
                t0Ns = nowNs
                activeGeneration = generation
                events.clear()
            }
            if (events.size >= MAX_EVENTS) events.removeFirst()
            // Sanitize: no path-like text (slashes / ewm_src).
            val safeId = importId
                .replace('/', '_')
                .replace("ewm_src_", "id_")
                .take(48)
            events.addLast(Event(name, generation, safeId, (nowNs - t0Ns) / 1_000_000L))
        } finally {
            lock.unlock()
        }
    }

    fun snapshot(): List<Event> {
        lock.lock()
        try {
            return events.toList()
        } finally {
            lock.unlock()
        }
    }

    fun names(): List<String> = snapshot().map { it.name }

    /** Host Pending chrome clock: process-relative monotonic ms (not wall clock). */
    fun nowMonoMsForHost(): Long = monoNs() / 1_000_000L

    fun containsTimelineInOrder(required: List<String>): Boolean {
        val names = names()
        var i = 0
        for (need in required) {
            while (i < names.size && names[i] != need) i++
            if (i >= names.size) return false
            i++
        }
        return true
    }

    /** Format deltas for artifacts (no paths). */
    fun formatTimeline(): String = snapshot().joinToString("\n") {
        "+${it.monoMs}ms\t${it.name}\tg=${it.generation}\tid=${it.importId}"
    }

    private fun monoNs(): Long = memScoped {
        val ts = alloc<timespec>()
        // clockid_t is platform.posix.clockid_t (UInt on Apple).
        clock_gettime(CLOCK_MONOTONIC.convert(), ts.ptr)
        ts.tv_sec * 1_000_000_000L + ts.tv_nsec
    }
}
