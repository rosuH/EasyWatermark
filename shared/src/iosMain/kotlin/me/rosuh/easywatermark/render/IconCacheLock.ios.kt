package me.rosuh.easywatermark.render

import platform.Foundation.NSLock

internal actual class IconCacheLock actual constructor() {
    private val lock = NSLock()

    actual fun <T> withLock(block: () -> T): T {
        lock.lock()
        return try {
            block()
        } finally {
            lock.unlock()
        }
    }
}
