package me.rosuh.easywatermark

import android.app.Application
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.util.Log
import androidx.core.content.edit
import me.rosuh.easywatermark.model.WaterMarkConfig
import kotlin.system.exitProcess


class MyApp : Application() {
    override fun onCreate() {
        super.onCreate()
        instance = this
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
                    KEY_STACK_TRACE, """
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
            exitProcess(0)
        }
    }

    companion object {
        lateinit var instance: Context
            private set

        fun globalSp(): SharedPreferences {
            return instance.getSharedPreferences(
                WaterMarkConfig.SP_NAME,
                MODE_PRIVATE
            )
        }

        const val SP_NAME = "sp_water_mark_crash_info"

        const val KEY_IS_CRASH = SP_NAME + "_key_is_crash"
        const val KEY_STACK_TRACE = SP_NAME + "_key_stack_trace"
    }
}