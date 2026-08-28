package me.rosuh.easywatermark.render

internal actual fun <T> withComposeTextCellLock(block: () -> T): T =
    synchronized(ComposeTextCellLockMonitor) { block() }

private object ComposeTextCellLockMonitor
