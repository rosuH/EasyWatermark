package me.rosuh.easywatermark.render

import platform.Foundation.NSRecursiveLock

private val composeTextCellLock = NSRecursiveLock()

internal actual fun <T> withComposeTextCellLock(block: () -> T): T {
    composeTextCellLock.lock()
    return try {
        block()
    } finally {
        composeTextCellLock.unlock()
    }
}
