package me.rosuh.easywatermark.platform

/**
 * Platform-neutral capability for Material You dynamic color (ADR-0007, ADR-0005).
 *
 * Android's actual implementation keeps the OEM allowlist + persisted user toggle (delegating to the
 * `:cmonet` module); Desktop/iOS bind an implementation that returns `false` / no-ops, falling back to
 * the static color schemes in `Theme.kt`.
 *
 * S4d-43 introduces this seam and routes only the live Compose call sites (theme gate + About toggle)
 * through it; the Android `ContextExtension` color getters and `MyApp.init` still call `:cmonet`
 * directly (deferred — see the slice plan).
 */
interface DynamicColorCapability {
    /** True if Material You dynamic color should be applied on this platform/device for the current user setting. */
    fun isAvailable(): Boolean

    /** Persisted user override of the device allowlist. No-op on platforms without dynamic color. */
    fun setForcedSupport(enabled: Boolean)
}
