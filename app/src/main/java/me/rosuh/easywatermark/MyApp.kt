package me.rosuh.easywatermark

import android.app.Application
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.content.edit
import kotlin.system.exitProcess


class MyApp : Application() {
    override fun onCreate() {
        super.onCreate()
        instance = this
        catchException()
    }

    private fun catchException() {
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            getSharedPreferences(SP_NAME, MODE_PRIVATE).edit(true) {
                putBoolean(KEY_IS_CRASH, true)
                putString(
                    KEY_STACK_TRACE, """
                    Crash in ${t.name}:
                    ${Log.getStackTraceString(e)}
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

        const val SP_NAME = "sp_water_mark_crash_info"

        const val KEY_IS_CRASH = SP_NAME + "_key_is_crash"
        const val KEY_STACK_TRACE = SP_NAME + "_key_stack_trace"
    }
}