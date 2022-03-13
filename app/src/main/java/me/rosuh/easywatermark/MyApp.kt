package me.rosuh.easywatermark

import android.annotation.SuppressLint
import android.app.Application
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.util.Log
import androidx.core.content.edit
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.*
import me.rosuh.cmonet.CMonet
import me.rosuh.easywatermark.data.repo.WaterMarkRepository
import me.rosuh.easywatermark.ui.theme.ThemeManager
import javax.inject.Inject
import kotlin.system.exitProcess

@HiltAndroidApp
class MyApp : Application() {

    @Inject
    lateinit var waterMarkRepo: WaterMarkRepository

    private val sp by lazy { getSharedPreferences(SP_NAME, Context.MODE_PRIVATE) }

    override fun attachBaseContext(base: Context?) {
        super.attachBaseContext(base)
        instance = this
        catchException()
    }

    override fun onCreate() {
        super.onCreate()
        val crashCount = sp.getInt(SP_KEY_CRASH_COUNT, 0)
        if (crashCount >= 2) {
            recoveryMode = true
            return
        } else {
            applicationScope.launch {
                waterMarkRepo.resetModeToText()
            }
            CMonet.init(this, true)
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        ThemeManager.onConfigurationChanged(newConfig)
        super.onConfigurationChanged(newConfig)
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

        val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

        @SuppressLint("StaticFieldLeak")
        lateinit var instance: Context
            private set

        var recoveryMode = false
            private set

        const val SP_NAME = "sp_water_mark_crash_info"

        const val KEY_IS_CRASH = SP_NAME + "_key_is_crash"
        const val KEY_STACK_TRACE = SP_NAME + "_key_stack_trace"
        const val SP_KEY_CRASH_COUNT = SP_NAME + "_key_crash_count"
    }
}
