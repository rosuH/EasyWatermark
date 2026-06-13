package me.rosuh.easywatermark.data.model

/**
 * Platform-neutral per-image export job state. Lives in `:shared/commonMain` (CMP plan).
 * Pure Kotlin; wraps [Result]. Compiles for Android + JVM/desktop.
 */
sealed class JobState {
    object Ready : JobState()

    object Ing : JobState()

    class Success(val result: Result<*>) : JobState()

    class Failure(val result: Result<*>) : JobState()
}
