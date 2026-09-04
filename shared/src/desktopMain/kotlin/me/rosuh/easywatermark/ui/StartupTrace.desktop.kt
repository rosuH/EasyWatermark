package me.rosuh.easywatermark.ui

internal actual fun startupTraceEnabled(): Boolean {
    val env = System.getenv("EWM_STARTUP_TRACE")
    if (env == "1" || env.equals("true", ignoreCase = true)) return true
    return System.getProperty("ewm.startup.trace") == "true"
}

internal actual fun startupTraceEmit(line: String) {
    println(line)
}
