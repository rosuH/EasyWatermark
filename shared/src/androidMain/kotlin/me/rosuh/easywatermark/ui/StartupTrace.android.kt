package me.rosuh.easywatermark.ui

import android.util.Log

internal actual fun startupTraceEnabled(): Boolean {
    if (Log.isLoggable(StartupTrace.ANDROID_LOG_TAG, Log.DEBUG)) return true
    return readDebugProperty("debug.ewm.startup_trace") == "1"
}

internal actual fun startupTraceEmit(line: String) {
    Log.i(StartupTrace.ANDROID_LOG_TAG, line)
    println(line)
}

private fun readDebugProperty(key: String): String? = try {
    val clazz = Class.forName("android.os.SystemProperties")
    val get = clazz.getMethod("get", String::class.java, String::class.java)
    get.invoke(null, key, "") as? String
} catch (_: Throwable) {
    null
}
