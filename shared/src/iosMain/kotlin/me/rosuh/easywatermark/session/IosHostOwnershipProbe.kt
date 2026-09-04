package me.rosuh.easywatermark.session

import platform.Foundation.NSLock

/**
 * Test seam: pause **after** Session publication returns and **before** the host adopts
 * ownership of newly published `ewm_src_*` paths (post-publication / pre-registration window).
 *
 * Production leaves the hook null.
 */
/** J5: test seam — not part of the Swift product API surface. */
internal object IosHostOwnershipProbe {
    private val lock = NSLock()
    private var beforeAdopt: (suspend () -> Unit)? = null

    fun install(beforeAdopt: suspend () -> Unit) {
        lock.lock()
        try {
            this.beforeAdopt = beforeAdopt
        } finally {
            lock.unlock()
        }
    }

    fun clear() {
        lock.lock()
        try {
            beforeAdopt = null
        } finally {
            lock.unlock()
        }
    }

    suspend fun awaitBeforeAdopt() {
        val hook = snapshot()
        hook?.invoke()
    }

    private fun snapshot(): (suspend () -> Unit)? {
        lock.lock()
        try {
            return beforeAdopt
        } finally {
            lock.unlock()
        }
    }
}
