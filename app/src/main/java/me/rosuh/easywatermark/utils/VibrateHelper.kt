package me.rosuh.easywatermark.utils

import android.view.HapticFeedbackConstants
import android.view.View

class VibrateHelper private constructor() {

    private var latestVibration: Long = 0L
    private var cd: Long = 20L

    fun doVibrate(view: View) {
        if (android.os.Build.VERSION.SDK_INT <= android.os.Build.VERSION_CODES.M) {
            return
        }
        if (System.currentTimeMillis() - latestVibration <= cd) {
            return
        }
        view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
    }

    companion object {
        fun get(): VibrateHelper {
            return VibrateHelper()
        }
    }
}
