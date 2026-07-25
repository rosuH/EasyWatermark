package me.rosuh.easywatermark.platform

import android.content.ContentResolver
import android.provider.Settings
import me.rosuh.easywatermark.ui.theme.MotionPolicy
import me.rosuh.easywatermark.ui.theme.resolveMotionPolicy

/**
 * Android: [Settings.Global.ANIMATOR_DURATION_SCALE] (0 = Off, low = Reduced).
 * Optional Secure reduce-motion style flag when present (best-effort).
 *
 * Uses application [ContentResolver] via [AndroidMotionContentResolver] set from app startup,
 * or falls back to Full when unset (unit tests / early init).
 */
actual fun platformMotionPolicy(): MotionPolicy {
    val resolver = AndroidMotionContentResolver.resolver ?: return MotionPolicy.Full
    return androidMotionPolicy(resolver)
}

fun androidMotionPolicy(resolver: ContentResolver): MotionPolicy {
    val scale = runCatching {
        Settings.Global.getFloat(resolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f)
    }.getOrDefault(1f)
    val reduce = runCatching {
        // Best-effort; not all OEM builds expose this Secure key.
        Settings.Secure.getInt(resolver, "accessibility_reduce_animation", 0) == 1 ||
            Settings.Secure.getInt(resolver, "reduce_motion", 0) == 1
    }.getOrDefault(false)
    return resolveMotionPolicy(animatorScale = scale, prefersReducedMotion = reduce)
}

/**
 * Process-wide ContentResolver for [platformMotionPolicy].
 * Set from [me.rosuh.easywatermark.MyApp] or first Activity; optional for tests.
 */
object AndroidMotionContentResolver {
    @Volatile
    var resolver: ContentResolver? = null

    fun install(resolver: ContentResolver) {
        this.resolver = resolver
    }
}
