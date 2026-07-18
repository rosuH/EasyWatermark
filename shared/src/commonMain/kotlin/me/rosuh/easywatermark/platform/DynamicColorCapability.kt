package me.rosuh.easywatermark.platform

/**
 * Platform gate for Material You / forced dynamic color.
 *
 * Android implements via `:cmonet`; other platforms no-op. Prefer this over direct CMonet access.
 */
interface DynamicColorCapability {
    /** True if Material You dynamic color should be applied on this platform/device for the current user setting. */
    fun isAvailable(): Boolean

    /**
 * Persisted "Force Open Dynamic Color" toggle (About switch).
 * Distinct from [isAvailable]: on allowlisted devices [isAvailable] stays true even when force is off.
     */
    fun isForcedSupport(): Boolean

    /** Persisted user override of the device allowlist. No-op on platforms without dynamic color. */
    fun setForcedSupport(enabled: Boolean)
}
