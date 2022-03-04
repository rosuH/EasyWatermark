package me.rosuh.easywatermark

import android.annotation.SuppressLint
import android.app.Application
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.util.Log
import androidx.core.content.edit
import com.google.android.material.color.DynamicColors
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.*
import me.rosuh.cmonet.CMonet
import me.rosuh.easywatermark.data.repo.WaterMarkRepository
import javax.inject.Inject
import kotlin.system.exitProcess

@HiltAndroidApp
class MyApp : Application() {

    @Inject
    lateinit var waterMarkRepo: WaterMarkRepository

    override fun onCreate() {
        super.onCreate()
        CMonet.applyToActivitiesIfAvailable(this)
        instance = this
        applicationScope.launch {
            waterMarkRepo.resetModeToText()
        }
        catchException()
    }

    private fun catchException() {
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            // Because intent limit data to 1mb, so that we should limit the stack track by magic number below
            val maxStringLength = 1024 * 1024 / 2 / 10 // the 10 is a magic number ;)
            var fullStackTrace = Log.getStackTraceString(e)
            if (fullStackTrace.length > maxStringLength) {
                fullStackTrace = fullStackTrace.substring(IntRange(0, maxStringLength))
            }
            getSharedPreferences(SP_NAME, MODE_PRIVATE).edit(true) {
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

        const val SP_NAME = "sp_water_mark_crash_info"

        const val KEY_IS_CRASH = SP_NAME + "_key_is_crash"
        const val KEY_STACK_TRACE = SP_NAME + "_key_stack_trace"
    }
}
