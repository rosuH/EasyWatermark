package me.rosuh.cmonet

import android.app.Application
import android.util.Log
import com.google.android.material.color.DynamicColors

object CMonet {

    private const val TAG = "CMonet"

    private lateinit var monetManufacturer: MonetManufacturer

    private lateinit var application: Application

    fun init(application: Application, apply: Boolean = true) {
        Log.d(TAG, "init")
        this.application = application
        monetManufacturer = MonetManufacturer(application)
        if (apply) {
            applyToActivitiesIfAvailable(application)
        }
    }

    private fun applyToActivitiesIfAvailable(application: Application) {
        Log.d(TAG, "applyToActivitiesIfAvailable")
        if (isDynamicColorAvailable()) {
            DynamicColors.applyToActivitiesIfAvailable(application)
        }
    }

    fun isDynamicColorAvailable(): Boolean {
        val isDynamicColorAvailable = monetManufacturer.isDynamicColorAvailable()
        Log.d(TAG, "isDynamicColorAvailable $isDynamicColorAvailable")
        return isDynamicColorAvailable
    }

    fun forceSupportDynamicColor() {
        Log.d(TAG, "forceSupportDynamicColor")
        monetManufacturer.setForceSupport(true)
    }

    fun disableSupportDynamicColor() {
        Log.d(TAG, "disableSupportDynamicColor")
        monetManufacturer.setForceSupport(false)
    }
}