package me.rosuh.easywatermark.session

import kotlin.concurrent.AtomicInt

/**
 * G4 test seam: observes peak concurrent [IosSourceStager.stageBytes] writers during
 * multi-item stage. Production leaves counters at zero between batches; tests may reset.
 *
 * Not a product API — max concurrent writers is gated by [IOS_STAGING_MAX_CONCURRENCY].
 */
/** J5: implementation/test seam — not part of the Swift product API surface. */
internal object IosStageConcurrencyProbe {
    private val inFlight = AtomicInt(0)
    private val peak = AtomicInt(0)

    fun reset() {
        inFlight.value = 0
        peak.value = 0
    }

    fun onEnter() {
        val now = inFlight.addAndGet(1)
        // CAS-style peak update without locks.
        while (true) {
            val p = peak.value
            if (now <= p) break
            if (peak.compareAndSet(p, now)) break
        }
    }

    fun onExit() {
        inFlight.addAndGet(-1)
    }

    fun peakInFlight(): Int = peak.value

    fun currentInFlight(): Int = inFlight.value
}
