package me.rosuh.easywatermark.ui

import platform.Foundation.NSLog
import platform.Foundation.NSProcessInfo

internal actual fun startupTraceEnabled(): Boolean =
    NSProcessInfo.processInfo.arguments.any { it?.toString() == "-ewmStartupTrace" }

internal actual fun startupTraceEmit(line: String) {
    println(line)
    NSLog("%s", line)
}
