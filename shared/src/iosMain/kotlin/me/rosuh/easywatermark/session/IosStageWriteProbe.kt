package me.rosuh.easywatermark.session

import platform.Foundation.NSLock

/**
 * Test seam: pause **after** a durable [IosSourceStager.stageBytes] write and **before**
 * ownership registration is considered complete by the outer cleanup path.
 *
 * Production leaves the hook null. Used to force cancellation/failure windows that must not
 * leave untracked `ewm_src_*` files.
 */
/** J5: test seam — not part of the Swift product API surface. */
internal object IosStageWriteProbe {
    private val lock = NSLock()
    private var afterWrite: (suspend (path: String) -> Unit)? = null

    fun install(afterWrite: suspend (path: String) -> Unit) {
        lock.lock()
        try {
            this.afterWrite = afterWrite
        } finally {
            lock.unlock()
        }
    }

    fun clear() {
        lock.lock()
        try {
            afterWrite = null
        } finally {
            lock.unlock()
        }
    }

    suspend fun awaitAfterWrite(path: String) {
        val hook = snapshot()
        hook?.invoke(path)
    }

    private fun snapshot(): (suspend (path: String) -> Unit)? {
        lock.lock()
        try {
            return afterWrite
        } finally {
            lock.unlock()
        }
    }
}
