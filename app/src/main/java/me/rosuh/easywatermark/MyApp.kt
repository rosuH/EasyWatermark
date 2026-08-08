package me.rosuh.easywatermark

import android.annotation.SuppressLint
import android.app.Application
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.content.edit
import me.rosuh.cmonet.CMonet
import me.rosuh.easywatermark.di.appModule
import org.koin.android.ext.koin.androidContext
import org.koin.android.ext.koin.androidLogger
import org.koin.core.context.startKoin
import kotlin.system.exitProcess

class MyApp : Application() {
    private val sp by lazy { getSharedPreferences(SP_NAME, Context.MODE_PRIVATE) }

    override fun attachBaseContext(base: Context?) {
        super.attachBaseContext(base)
        instance = this
        catchException()
    }

    override fun onCreate() {
        super.onCreate()
        // DEBUG-only recomposition tracing for compose-stability-analyzer / IDE heatmap.
        // Release keeps the gate off so TraceRecomposition residual is map-lookup + early return.
        com.skydoves.compose.stability.runtime.ComposeStabilityAnalyzer.setEnabled(BuildConfig.DEBUG)
        startKoin {
            // 将 Koin 日志记录到 Android logger
            androidLogger()
            // 引用 Android 上下文
            androidContext(this@MyApp)
            modules(appModule)
        }
        CMonet.init(this, true)
        // I3: ContentResolver for platformMotionPolicy (animator scale / reduce motion).
        me.rosuh.easywatermark.platform.AndroidMotionContentResolver.install(contentResolver)
        if (checkRecoveryMode()) return
    }

    private fun checkRecoveryMode(): Boolean {
        val crashCount = sp.getInt(SP_KEY_CRASH_COUNT, 0)
        if (crashCount < CRASH_COUNT) {
            return false
        }
        val recoveryVersion = sp.getInt(SP_KEY_RECOVERY_VERSION, BuildConfig.VERSION_CODE - 1)
        if (recoveryVersion < BuildConfig.VERSION_CODE) {
            // maybe we fixed in this version
            recoveryMode = false
            sp.edit {
                putInt(SP_KEY_CRASH_COUNT, 0)
                putInt(SP_KEY_RECOVERY_VERSION, 0)
            }
            return false
        }
        recoveryMode = true
        sp.edit {
            putInt(SP_KEY_RECOVERY_VERSION, BuildConfig.VERSION_CODE)
        }
        return true
    }

    fun launchSuccess() {
        recoveryMode = false
        val sp = getSharedPreferences(SP_NAME, Context.MODE_PRIVATE)
        sp.edit {
            putInt(SP_KEY_CRASH_COUNT, 0)
        }
    }

    private fun catchException() {
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            // Because intent limit data to 1mb, so that we should limit the stack track by magic number below
            Log.e("MyApp", "uncaughtException")
            val maxStringLength = 1024 * 1024 / 2 / 10 // the 10 is a magic number ;)
            var fullStackTrace = Log.getStackTraceString(e)
            if (fullStackTrace.length > maxStringLength) {
                fullStackTrace = fullStackTrace.substring(IntRange(0, maxStringLength))
            }
            Log.e("MyApp", "uncaughtException: $fullStackTrace")
            sp.edit(true) {
                putInt(SP_KEY_CRASH_COUNT, sp.getInt(SP_KEY_CRASH_COUNT, 0) + 1)
                putInt(SP_KEY_RECOVERY_VERSION, BuildConfig.VERSION_CODE)
                putBoolean(KEY_IS_CRASH, true)
                putString(
                    KEY_STACK_TRACE,
                    """
                    Crash in ${t.name}:
                    $fullStackTrace
                    """.trimIndent()
                )
            }
            with(Intent(Intent.ACTION_MAIN)) {
                addCategory(Intent.CATEGORY_HOME)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
                this@MyApp.startActivity(this)
            }
            e.printStackTrace()
            exitProcess(0)
        }
    }

    companion object {

        @SuppressLint("StaticFieldLeak")
        lateinit var instance: Context
            private set

        var recoveryMode = false
            private set

        /**
 * Enter recovery after this many uncaught crashes without a long stable window.
 * [me.rosuh.easywatermark.ui.ComposeMainActivity.onResume] clears the counter only after
 * 30s of stable foreground — so a crash-loop on pick→editor still accumulates.
         */
        private const val CRASH_COUNT = 2

        const val SP_NAME = "sp_water_mark_crash_info"

        const val KEY_IS_CRASH = SP_NAME + "_key_is_crash"
        const val KEY_STACK_TRACE = SP_NAME + "_key_stack_trace"
        const val SP_KEY_CRASH_COUNT = SP_NAME + "_key_crash_count"
        const val SP_KEY_RECOVERY_VERSION = SP_NAME + "_key_recovery_version"
    }
}
