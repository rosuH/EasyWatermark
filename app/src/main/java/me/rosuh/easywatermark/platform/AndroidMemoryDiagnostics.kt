package me.rosuh.easywatermark.platform

import android.app.ActivityManager
import android.app.Application
import android.app.ApplicationExitInfo
import android.content.Context
import android.os.Build
import android.os.ProfilingManager
import android.os.ProfilingResult
import android.os.ProfilingTrigger
import android.util.Log
import androidx.annotation.RequiresApi
import me.rosuh.easywatermark.BuildConfig
import java.io.File
import java.util.concurrent.Executors
import java.util.function.Consumer

/**
 * DEBUG-only memory observability for Android 17 memory-limiter kills.
 *
 * Privacy: logcat + optional **local** files under app cache only. No network upload,
 * no analytics, no crash SDK.
 */
object AndroidMemoryDiagnostics {
    private const val TAG = "EwmMemoryLimiter"
    private const val DUMP_DIR_NAME = "ewm-memory-dumps"

    @Volatile
    private var profilingListener: Consumer<ProfilingResult>? = null

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
     * Best-effort [ProfilingManager] wiring (DEBUG only):
     * - API 35+: [ProfilingManager.registerForAllProfilingResults] → log local path only
     * - API 36+: [ProfilingManager.addProfilingTriggers]
     * - API 37+: [ProfilingTrigger.TRIGGER_TYPE_ANOMALY] + [ProfilingTrigger.TRIGGER_TYPE_OOM]
     *
     * Soft-fails on missing service / OEM gaps. **No upload.**
     */
    fun registerLocalProfilingTriggers(app: Application) {
        if (!BuildConfig.DEBUG) return
        if (Build.VERSION.SDK_INT < 35) {
            Log.i(TAG, "ProfilingManager skipped (API ${Build.VERSION.SDK_INT} < 35)")
            return
        }
        try {
            registerProfilingApi35(app)
        } catch (t: Throwable) {
            Log.w(TAG, "ProfilingManager register soft-fail: ${t.message}")
        }
    }

    @RequiresApi(35)
    private fun registerProfilingApi35(app: Application) {
        val pm = app.getSystemService(ProfilingManager::class.java)
        if (pm == null) {
            Log.i(TAG, "ProfilingManager service null")
            return
        }
        val dumpDir = File(app.cacheDir, DUMP_DIR_NAME).apply { mkdirs() }
        val executor = Executors.newSingleThreadExecutor { r ->
            Thread(r, "ewm-profiling").apply { isDaemon = true }
        }
        // Drop previous listener if Application recreated in process (tests / rare).
        profilingListener?.let { prev ->
            try {
                pm.unregisterForAllProfilingResults(prev)
            } catch (_: Throwable) {
                // ignore
            }
        }
        val listener = Consumer<ProfilingResult> { result ->
            try {
                handleProfilingResult(dumpDir, result)
            } catch (t: Throwable) {
                Log.w(TAG, "ProfilingResult handler fail: ${t.message}")
            }
        }
        profilingListener = listener
        pm.registerForAllProfilingResults(executor, listener)

        if (Build.VERSION.SDK_INT >= 36) {
            val triggerSummary = addMemoryRelatedTriggers(pm)
            Log.i(
                TAG,
                "ProfilingManager registered triggers=[$triggerSummary] " +
                    "local dump dir=${dumpDir.absolutePath} (no upload)",
            )
        } else {
            Log.i(
                TAG,
                "ProfilingManager result listener registered (API 35 — no trigger API); " +
                    "local dump dir=${dumpDir.absolutePath} (no upload)",
            )
        }
    }

    /**
     * @return human-readable trigger list for logcat
     */
    @RequiresApi(36)
    private fun addMemoryRelatedTriggers(pm: ProfilingManager): String {
        val triggers = ArrayList<ProfilingTrigger>()
        val names = ArrayList<String>()
        if (Build.VERSION.SDK_INT >= 37) {
            triggers.add(
                ProfilingTrigger.Builder(ProfilingTrigger.TRIGGER_TYPE_ANOMALY).build(),
            )
            names.add("ANOMALY")
            triggers.add(
                ProfilingTrigger.Builder(ProfilingTrigger.TRIGGER_TYPE_OOM).build(),
            )
            names.add("OOM")
        } else {
            // API 36: no ANOMALY/OOM constants; keep listener path only for memory kills.
            // Optionally register ANR so the trigger plumbing is exercised on 36 devices.
            triggers.add(
                ProfilingTrigger.Builder(ProfilingTrigger.TRIGGER_TYPE_ANR).build(),
            )
            names.add("ANR")
        }
        try {
            pm.clearProfilingTriggers()
        } catch (_: Throwable) {
            // clear may be rate-limited or unsupported; still try add
        }
        pm.addProfilingTriggers(triggers)
        return names.joinToString(",")
    }

    private fun handleProfilingResult(dumpDir: File, result: ProfilingResult) {
        val path = result.resultFilePath
        val err = result.errorCode
        val tag = result.tag
        val trigger = if (Build.VERSION.SDK_INT >= 36) {
            try {
                result.triggerType.toString()
            } catch (_: Throwable) {
                "?"
            }
        } else {
            "n/a"
        }
        if (err == ProfilingResult.ERROR_NONE) {
            Log.i(
                TAG,
                "ProfilingResult OK path=$path tag=$tag triggerType=$trigger " +
                    "localNoteDir=${dumpDir.absolutePath}",
            )
            if (!path.isNullOrBlank()) {
                runCatching {
                    File(dumpDir, "last-result-path.txt").writeText(
                        "path=$path\ntag=$tag\ntriggerType=$trigger\nts=${System.currentTimeMillis()}\n",
                    )
                }
            }
        } else {
            Log.w(
                TAG,
                "ProfilingResult error=$err msg=${result.errorMessage} " +
                    "tag=$tag triggerType=$trigger path=$path",
            )
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
