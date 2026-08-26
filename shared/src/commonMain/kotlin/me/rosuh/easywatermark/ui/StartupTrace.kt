package me.rosuh.easywatermark.ui

import kotlin.time.TimeSource

/**
 * Gated cold-start probe. Off unless the platform enable flag is set.
 *
 * Line format (one mark per line, monotonic ms from first in-process read):
 * `EWM_STARTUP mark=<name> t_ms=<int>`
 *
 * Metric names (same on Android / Desktop / iOS Kotlin):
 * - `app_create_start` / `app_create_end` — process graph (Koin / DataStores / Session)
 * - `host_create_start` / `host_set_content` — Activity / window / ComposeUIViewController
 * - `shell_composed` — first [ProductShellHost] composition
 * - `first_compose_frame` — first Choreographer / display frame after the shell
 * - `launch_composed` — first [LaunchScreen] composition
 * - `first_screen` — Launch laid out (logo + pick CTA in the tree)
 * - `cold_reveal_done` — process-first fade settled or skipped
 * - `mesh_ready` — BrandLogo decorative mesh armed (or skipped)
 * - `fully_drawn` — first_screen + reveal settled ([Activity.reportFullyDrawn] on Android)
 */
object StartupTrace {
    const val LINE_PREFIX: String = "EWM_STARTUP"
    const val ANDROID_LOG_TAG: String = "EwmStartup"

    private val epoch = TimeSource.Monotonic.markNow()
    private val seen = HashSet<String>()
    private var firstScreen = false
    private var revealDone = false

    /** Android host sets this to [android.app.Activity.reportFullyDrawn]. */
    var fullyDrawnListener: (() -> Unit)? = null

    /** Android host sets this to drop the system splash after Launch layout. */
    var firstScreenListener: (() -> Unit)? = null

    fun isEnabled(): Boolean = startupTraceEnabled()

    fun elapsedMs(): Long = epoch.elapsedNow().inWholeMilliseconds

    fun mark(name: String) {
        if (!isEnabled()) return
        emit(name)
    }

    fun markOnce(name: String) {
        if (!isEnabled()) return
        if (!seen.add(name)) return
        emit(name)
    }

    fun onFirstScreen() {
        markOnce("first_screen")
        if (firstScreen) return
        firstScreen = true
        firstScreenListener?.invoke()
        maybeFullyDrawn()
    }

    fun onColdRevealDone() {
        markOnce("cold_reveal_done")
        revealDone = true
        maybeFullyDrawn()
    }

    fun resetForTests() {
        seen.clear()
        firstScreen = false
        revealDone = false
        fullyDrawnListener = null
        firstScreenListener = null
    }

    private fun maybeFullyDrawn() {
        val ready = firstScreen && revealDone && seen.add("fully_drawn")
        if (!ready) return
        emit("fully_drawn")
        fullyDrawnListener?.invoke()
    }

    private fun emit(name: String) {
        startupTraceEmit("$LINE_PREFIX mark=$name t_ms=${elapsedMs()}")
    }
}

internal expect fun startupTraceEnabled(): Boolean

internal expect fun startupTraceEmit(line: String)
