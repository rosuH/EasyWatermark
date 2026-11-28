package me.rosuh.easywatermark.render

internal expect class IconCacheLock() {
    fun <T> withLock(block: () -> T): T
}
