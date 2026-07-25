package me.rosuh.easywatermark.session

import platform.Foundation.NSLock
import kotlin.experimental.ExperimentalObjCName
import kotlin.native.ObjCName

/**
 * Issue 26 / C4.4R.S1 — **sole process-wide photo/icon pick generation issuer** (F14).
 *
 * Kotlin issues monotonic generation tokens; Swift only stores the returned value for its FIFO
 * commit serial. Publication checks use the same owner under [NSLock] so MainActor writers and
 * Default-dispatcher readers share a coherent lifecycle (survives SwiftUI state recreation).
 *
 * F15/F16: icon generations are issued and consumed at icon config publication
 * ([me.rosuh.easywatermark.ui.IosProductRootHost.deliverIconBytesAndAwait]).
 */
@OptIn(ExperimentalObjCName::class)
@ObjCName(name = "IosPickGenerationGate", exact = true)
object IosPickGenerationGate {
    private val lock = NSLock()
    private var photoLatest: Long = 0L
    private var iconLatest: Long = 0L

    /** Issue the next photo-pick generation (monotonic, process-wide). */
    fun nextPhotoGeneration(): Long {
        lock.lock()
        try {
            photoLatest += 1L
            return photoLatest
        } finally {
            lock.unlock()
        }
    }

    fun isPhotoCurrent(generation: Long): Boolean {
        lock.lock()
        try {
            return generation == photoLatest
        } finally {
            lock.unlock()
        }
    }

    fun currentPhotoGeneration(): Long {
        lock.lock()
        try {
            return photoLatest
        } finally {
            lock.unlock()
        }
    }

    fun nextIconGeneration(): Long {
        lock.lock()
        try {
            iconLatest += 1L
            return iconLatest
        } finally {
            lock.unlock()
        }
    }

    fun isIconCurrent(generation: Long): Boolean {
        lock.lock()
        try {
            return generation == iconLatest
        } finally {
            lock.unlock()
        }
    }

    /** Test-only reset between isolated graphs. */
    fun resetForTests() {
        lock.lock()
        try {
            photoLatest = 0L
            iconLatest = 0L
        } finally {
            lock.unlock()
        }
    }
}

/** Thrown when a pick generation is no longer live at a publication boundary. */
class StalePickGenerationException(
    val generation: Long,
    message: String = "stale pick generation $generation",
) : Exception(message)

/**
 * Test probes that pause **immediately before** production publication writes.
 * Production leaves all hooks null. Used by F12/F16 seam tests.
 */
/** J5: test seam — not part of the Swift product API surface. */
internal object IosPickPublishProbe {
    private val lock = NSLock()
    private var beforeGuardedPublish: (suspend (generation: Long) -> Unit)? = null
    private var beforeHostPreviewBind: (suspend (generation: Long) -> Unit)? = null
    private var beforeIconConfig: (suspend (generation: Long) -> Unit)? = null

    fun install(
        beforeGuardedPublish: (suspend (generation: Long) -> Unit)? = null,
        beforeHostPreviewBind: (suspend (generation: Long) -> Unit)? = null,
        beforeIconConfig: (suspend (generation: Long) -> Unit)? = null,
    ) {
        lock.lock()
        try {
            if (beforeGuardedPublish != null) {
                this.beforeGuardedPublish = beforeGuardedPublish
            }
            if (beforeHostPreviewBind != null) {
                this.beforeHostPreviewBind = beforeHostPreviewBind
            }
            if (beforeIconConfig != null) {
                this.beforeIconConfig = beforeIconConfig
            }
        } finally {
            lock.unlock()
        }
    }

    /** Backward-compatible single-hook install (Session publish boundary). */
    fun install(hook: suspend (generation: Long) -> Unit) {
        install(beforeGuardedPublish = hook)
    }

    suspend fun awaitBeforeGuardedPublish(generation: Long) {
        val hook = snapshot { beforeGuardedPublish }
        hook?.invoke(generation)
    }

    suspend fun awaitBeforeHostPreviewBind(generation: Long) {
        val hook = snapshot { beforeHostPreviewBind }
        hook?.invoke(generation)
    }

    suspend fun awaitBeforeIconConfig(generation: Long) {
        val hook = snapshot { beforeIconConfig }
        hook?.invoke(generation)
    }

    fun clear() {
        lock.lock()
        try {
            beforeGuardedPublish = null
            beforeHostPreviewBind = null
            beforeIconConfig = null
        } finally {
            lock.unlock()
        }
    }

    private fun <T> snapshot(read: IosPickPublishProbe.() -> T): T {
        lock.lock()
        try {
            return read()
        } finally {
            lock.unlock()
        }
    }
}
