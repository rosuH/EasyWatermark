package me.rosuh.easywatermark.utils

import android.os.Build
import android.view.HapticFeedbackConstants
import android.view.View

class VibrateHelper private constructor() {

    private var latestVibration: Long = 0L
    private var cd: Long = 20L

    fun doVibrate(view: View) {
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.M) {
            return
        }
        val now = System.currentTimeMillis()
        if (now - latestVibration <= cd) {
            return
        }
        latestVibration = now
        performHapticFeedbackSafely {
            view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
        }
    }

    companion object {
        fun get(): VibrateHelper {
            return VibrateHelper()
        }

        internal fun performHapticFeedbackSafely(performFeedback: () -> Boolean): Boolean {
            return try {
                performFeedback()
            } catch (_: SecurityException) {
                false
            }
        }
    }
}
