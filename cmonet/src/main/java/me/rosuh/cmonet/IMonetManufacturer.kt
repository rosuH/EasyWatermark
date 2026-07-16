package me.rosuh.cmonet

interface IMonetManufacturer {
    fun isDynamicColorAvailable(): Boolean

    /** Persisted "Force Open Dynamic Color" user toggle (not effective availability). */
    fun isForceSupport(): Boolean

    fun setForceSupport(supported: Boolean)
}