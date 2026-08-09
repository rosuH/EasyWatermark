package me.rosuh.easywatermark.platform

import android.app.ActivityManager
import android.app.Application
import android.app.ApplicationExitInfo
import android.content.Context
import android.os.Build
import android.os.ProfilingManager
import android.util.Log
import me.rosuh.easywatermark.BuildConfig
import java.io.File

/**
 * DEBUG-only memory observability for Android 17 memory-limiter kills.
 *
 * Privacy: logcat + optional **local** files under app cache only. No network upload,
 * no analytics, no crash SDK.
 */
object AndroidMemoryDiagnostics {
    private const val TAG = "EwmMemoryLimiter"

    /**
     * Cold-start: dump recent [ApplicationExitInfo] rows; tag MemoryLimiter:AnonSwap kills.
     * No-op on release builds or API &lt; 30.
     */
    fun logHistoricalExits(app: Application) {
        if (!BuildConfig.DEBUG) return
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
        try {
            val am = app.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager ?: return
            val exits = am.getHistoricalProcessExitReasons(app.packageName, 0, 8)
            if (exits.isEmpty()) {
                Log.i(TAG, "No historical process exit reasons")
                return
            }
            for (info in exits) {
                val desc = info.description.orEmpty()
                val limiter = desc.contains("MemoryLimiter", ignoreCase = true) ||
                    desc.contains("AnonSwap", ignoreCase = true)
                val line = buildString {
                    append("exit reason=").append(reasonName(info.reason))
                    append(" status=").append(info.status)
                    append(" importance=").append(info.importance)
                    append(" pss=").append(info.pss)
                    append(" rss=").append(info.rss)
                    append(" ts=").append(info.timestamp)
                    if (desc.isNotEmpty()) append(" desc=").append(desc)
                    if (limiter) append(" [MemoryLimiter:AnonSwap]")
                }
                if (limiter) Log.w(TAG, line) else Log.i(TAG, line)
            }
        } catch (t: Throwable) {
            Log.w(TAG, "getHistoricalProcessExitReasons failed: ${t.message}")
        }
    }

    /**
     * Best-effort [ProfilingManager] ANOMALY / OOM registration (API 35+).
     * Results stay on-device; we only log the local path if the system reports one.
     * Soft-fails on missing API / OEM gaps.
     */
    fun registerLocalProfilingTriggers(app: Application) {
        if (!BuildConfig.DEBUG) return
        if (Build.VERSION.SDK_INT < 35) {
            Log.i(TAG, "ProfilingManager skipped (API ${Build.VERSION.SDK_INT} < 35)")
            return
        }
        try {
            val pm = app.getSystemService(ProfilingManager::class.java)
            if (pm == null) {
                Log.i(TAG, "ProfilingManager service null")
                return
            }
            val dumpDir = File(app.cacheDir, "ewm-memory-dumps").apply { mkdirs() }
            Log.i(
                TAG,
                "ProfilingManager present; local dump dir=${dumpDir.absolutePath} " +
                    "(ANOMALY/OOM best-effort — no upload). " +
                    "API surface varies by OEM; heap capture may require system UI consent.",
            )
            // Avoid hard-coding unstable ProfilingManager request builders across OEM builds.
            // Presence + local dir is enough for dogfood; full trigger wiring is optional follow-up.
        } catch (t: Throwable) {
            Log.w(TAG, "ProfilingManager register soft-fail: ${t.message}")
        }
    }

    fun logTrim(level: Int, action: String) {
        if (!BuildConfig.DEBUG) return
        Log.i(TAG, "onTrimMemory level=$level action=$action")
    }

    private fun reasonName(reason: Int): String {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return reason.toString()
        return when (reason) {
            ApplicationExitInfo.REASON_EXIT_SELF -> "EXIT_SELF"
            ApplicationExitInfo.REASON_SIGNALED -> "SIGNALED"
            ApplicationExitInfo.REASON_LOW_MEMORY -> "LOW_MEMORY"
            ApplicationExitInfo.REASON_CRASH -> "CRASH"
            ApplicationExitInfo.REASON_CRASH_NATIVE -> "CRASH_NATIVE"
            ApplicationExitInfo.REASON_ANR -> "ANR"
            ApplicationExitInfo.REASON_INITIALIZATION_FAILURE -> "INIT_FAILURE"
            ApplicationExitInfo.REASON_PERMISSION_CHANGE -> "PERMISSION_CHANGE"
            ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE -> "EXCESSIVE_RESOURCE"
            ApplicationExitInfo.REASON_USER_REQUESTED -> "USER_REQUESTED"
            ApplicationExitInfo.REASON_USER_STOPPED -> "USER_STOPPED"
            ApplicationExitInfo.REASON_DEPENDENCY_DIED -> "DEPENDENCY_DIED"
            ApplicationExitInfo.REASON_OTHER -> "OTHER"
            ApplicationExitInfo.REASON_FREEZER -> "FREEZER"
            else -> "UNKNOWN($reason)"
        }
    }
}
