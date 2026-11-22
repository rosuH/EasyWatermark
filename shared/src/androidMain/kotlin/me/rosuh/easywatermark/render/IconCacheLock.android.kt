package me.rosuh.easywatermark.render

internal actual class IconCacheLock actual constructor() {
    actual fun <T> withLock(block: () -> T): T = synchronized(this) { block() }
}
