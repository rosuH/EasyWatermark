package me.rosuh.cmonet

interface IMonetManufacturer {
    fun isDynamicColorAvailable(): Boolean
    fun setForceSupport(supported: Boolean)
}