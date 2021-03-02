package me.rosuh.easywatermark.utils

import android.content.Context
import android.media.AudioAttributes
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator

class VibrateHelper private constructor(private val v: Vibrator) {

    private var latestVibration: Long = 0L
    private var cd: Long = 0L
    private var skipLowVersionDevices: Boolean = true

    private val audioAttributes by lazy {
        AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .setFlags(AudioAttributes.FLAG_AUDIBILITY_ENFORCED)
            .build()
    }

    fun doVibrate(shot: Long = 20L, amplitude: Int = 20) {
        if (!v.hasVibrator()
            || (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !v.hasAmplitudeControl())
            || System.currentTimeMillis() - latestVibration <= cd
            || canSkipLowVersionDevices()
        ) {
            return
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            v.cancel()
            v.vibrate(VibrationEffect.createOneShot(shot, amplitude), audioAttributes)
        } else {
            //deprecated in API 26
            v.vibrate(shot)
        }
        latestVibration = System.currentTimeMillis()
    }

    fun release() {
        v.cancel()
    }

    private fun canSkipLowVersionDevices(): Boolean {
        return skipLowVersionDevices && Build.VERSION.SDK_INT < Build.VERSION_CODES.O
    }

    companion object {
        fun init(
            context: Context,
            cd: Long = 50,
            skipLowVersionDevices: Boolean = true
        ): VibrateHelper {
            val vibrator = context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
            return VibrateHelper(vibrator).apply {
                this.cd = cd
                this.skipLowVersionDevices = skipLowVersionDevices
            }
        }
    }
}