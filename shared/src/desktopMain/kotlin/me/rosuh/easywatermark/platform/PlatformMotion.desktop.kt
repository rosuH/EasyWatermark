package me.rosuh.easywatermark.platform

import me.rosuh.easywatermark.ui.theme.MotionPolicy
import me.rosuh.easywatermark.ui.theme.motionPolicyFromReduceMotionFlag

/**
 * Desktop motion policy (M9):
 * 1. Env override for testing: `EWM_MOTION_POLICY=full|reduced|off`
 * 2. Best-effort macOS Accessibility reduce-motion via `defaults read`
 * 3. Otherwise [MotionPolicy.Full] (no portable JVM a11y API)
 *
 * Hosts may still [me.rosuh.easywatermark.ui.theme.ProvideMotionPolicy] an override.
 */
actual fun platformMotionPolicy(): MotionPolicy {
    envMotionPolicyOverride()?.let { return it }
    if (macOsPrefersReducedMotion()) {
        return motionPolicyFromReduceMotionFlag(true)
    }
    return MotionPolicy.Full
}

private fun envMotionPolicyOverride(): MotionPolicy? =
    when (System.getenv("EWM_MOTION_POLICY")?.trim()?.lowercase()) {
        "full" -> MotionPolicy.Full
        "reduced" -> MotionPolicy.Reduced
        "off" -> MotionPolicy.Off
        else -> null
    }

/**
 * Best-effort: `defaults read com.apple.universalaccess reduceMotion` → 1 means Reduced.
 * Cached process-wide; failures / non-mac → false (caller treats as Full).
 */
private fun macOsPrefersReducedMotion(): Boolean {
    cachedMacReduceMotion?.let { return it }
    val os = System.getProperty("os.name").orEmpty().lowercase()
    if (!os.contains("mac")) {
        cachedMacReduceMotion = false
        return false
    }
    val result = runCatching {
        val proc = ProcessBuilder(
            "defaults",
            "read",
            "com.apple.universalaccess",
            "reduceMotion",
        )
            .redirectErrorStream(true)
            .start()
        val out = proc.inputStream.bufferedReader().readText().trim()
        val code = proc.waitFor()
        code == 0 && (out == "1" || out.equals("true", ignoreCase = true))
    }.getOrDefault(false)
    cachedMacReduceMotion = result
    return result
}

@Volatile
private var cachedMacReduceMotion: Boolean? = null
