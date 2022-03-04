package me.rosuh.cmonet

import android.app.Application
import com.google.android.material.color.DynamicColors

object CMonet {

    private val monetManufacturer by lazy { MonetManufacturer() }

    fun isDynamicColorAvailable(): Boolean {
        return monetManufacturer.isDynamicColorAvailable()
    }

    fun forceSupportDynamicColor() {
        monetManufacturer.setForceSupport(true)
    }

    fun disableSupportDynamicColor() {
        monetManufacturer.setForceSupport(false)
    }

    fun applyToActivitiesIfAvailable(application: Application) {
        if (isDynamicColorAvailable()) {
            DynamicColors.applyToActivitiesIfAvailable(application)
        }
    }
}