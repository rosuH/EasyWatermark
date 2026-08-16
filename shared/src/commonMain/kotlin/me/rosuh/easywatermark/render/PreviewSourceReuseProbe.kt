package me.rosuh.easywatermark.render

import kotlin.concurrent.Volatile
import kotlin.experimental.ExperimentalObjCRefinement
import kotlin.native.HiddenFromObjC

/**
 * Opt-in decode / compose counters for preview source-reuse benches.
 *
 * Production default is **off**. Enable for Desktop/Android host benches and unit tests.
 */
@OptIn(ExperimentalObjCRefinement::class)
@HiddenFromObjC
object PreviewSourceReuseProbe {
    @Volatile
    var enabled: Boolean = false

    @Volatile private var sourceDecodes: Int = 0
    @Volatile private var iconDecodes: Int = 0
    @Volatile private var composes: Int = 0
    @Volatile private var contentResolverOpens: Int = 0
    @Volatile private var inFlightCompose: Int = 0
    @Volatile private var peakInFlightCompose: Int = 0

    fun reset() {
        sourceDecodes = 0
        iconDecodes = 0
        composes = 0
        contentResolverOpens = 0
        inFlightCompose = 0
        peakInFlightCompose = 0
    }

    fun recordSourceDecode() {
        if (enabled) sourceDecodes += 1
    }

    fun recordIconDecode() {
        if (enabled) iconDecodes += 1
    }

    fun recordCompose() {
        if (enabled) composes += 1
    }

    fun recordContentResolverOpen() {
        if (enabled) contentResolverOpens += 1
    }

    fun beginCompose() {
        if (!enabled) return
        val now = inFlightCompose + 1
        inFlightCompose = now
        if (now > peakInFlightCompose) peakInFlightCompose = now
    }

    fun endCompose() {
        if (enabled) inFlightCompose -= 1
    }

    fun snapshot(): Snapshot = Snapshot(
        sourceDecodes = sourceDecodes,
        iconDecodes = iconDecodes,
        composes = composes,
        contentResolverOpens = contentResolverOpens,
        inFlightCompose = inFlightCompose,
        peakInFlightCompose = peakInFlightCompose,
    )

    data class Snapshot(
        val sourceDecodes: Int,
        val iconDecodes: Int,
        val composes: Int,
        val contentResolverOpens: Int,
        val inFlightCompose: Int,
        val peakInFlightCompose: Int,
    )
}
