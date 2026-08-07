package me.rosuh.easywatermark.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf

/**
 * I3 — product motion intensity policy.
 *
 * | Policy | Behavior |
 * |---|---|
 * | [Full] | Current decorative + route + progress animations |
 * | [Reduced] | Shorter durations; no infinite decorative loops |
 * | [Off] | Instant (duration 0); no decorative loops |
 *
 * Hosts feed [LocalMotionPolicy] from platform reduce-motion / animator-scale
 * (see [motionPolicyFromAnimatorScale], [motionPolicyFromReduceMotionFlag]).
 */
enum class MotionPolicy {
    Full,
    Reduced,
    Off,
}

/** Default when no host [CompositionLocalProvider] is installed. */
val LocalMotionPolicy = staticCompositionLocalOf { MotionPolicy.Full }

@Composable
@ReadOnlyComposable
fun currentMotionPolicy(): MotionPolicy = LocalMotionPolicy.current

/**
 * Install [policy] for the subtree. Prefer wrapping product roots (Activity / Desktop window / iOS host).
 */
@Composable
fun ProvideMotionPolicy(policy: MotionPolicy, content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalMotionPolicy provides policy, content = content)
}

/**
 * Pure duration mapping.
 *
 * - [MotionPolicy.Full]: [fullMs]
 * - [MotionPolicy.Reduced]: `round(fullMs * reducedScale)`, never negative
 * - [MotionPolicy.Off]: `0` (instant)
 */
fun motionDurationMs(
    policy: MotionPolicy,
    fullMs: Int,
    reducedScale: Float = MotionPolicyDefaults.ReducedScale,
): Int {
    val safeFull = fullMs.coerceAtLeast(0)
    return when (policy) {
        MotionPolicy.Full -> safeFull
        MotionPolicy.Reduced -> (safeFull * reducedScale).toInt().coerceAtLeast(0)
        MotionPolicy.Off -> 0
    }
}

/** Infinite / decorative loops (e.g. BrandLogo sweep) only under [MotionPolicy.Full]. */
fun motionAllowsDecorativeLoop(policy: MotionPolicy): Boolean = policy == MotionPolicy.Full

/** Any non-instant transition (route fades, progress wipe). False only for [MotionPolicy.Off]. */
fun motionAllowsTimedAnimation(policy: MotionPolicy): Boolean = policy != MotionPolicy.Off

/**
 * Aspect-aware multi-image preview crossfade duration under [policy].
 * [aspectDelta] in 0..1 (0 = same aspect, 1 = extreme change). Off → 0.
 */
fun previewCrossfadeDurationMs(
    policy: MotionPolicy,
    aspectDelta: Float,
    minFullMs: Int = EwmMotionTokens.previewCrossfadeMinMs,
    maxFullMs: Int = EwmMotionTokens.previewCrossfadeMaxMs,
): Int {
    val minMs = motionDurationMs(policy, minFullMs)
    val maxMs = motionDurationMs(policy, maxFullMs)
    if (minMs <= 0 && maxMs <= 0) return 0
    val t = aspectDelta.coerceIn(0f, 1f)
    return (minMs + (maxMs - minMs) * t).toInt().coerceAtLeast(0)
}

/**
 * Map Android [Settings.Global.ANIMATOR_DURATION_SCALE] (and similar) to a policy.
 * - `0` → Off
 * - `(0, 0.5)` → Reduced
 * - else → Full (including default `1`)
 */
fun motionPolicyFromAnimatorScale(scale: Float): MotionPolicy = when {
    !scale.isFinite() || scale <= 0f -> MotionPolicy.Off
    scale < 0.5f -> MotionPolicy.Reduced
    else -> MotionPolicy.Full
}

/**
 * Map OS “reduce motion” boolean (iOS UIAccessibility, etc.).
 * Prefer [motionPolicyFromAnimatorScale] when a continuous scale is available.
 */
fun motionPolicyFromReduceMotionFlag(prefersReducedMotion: Boolean): MotionPolicy =
    if (prefersReducedMotion) MotionPolicy.Reduced else MotionPolicy.Full

/**
 * Combine animator scale + reduce-motion flag (stricter wins).
 * Desktop with no OS API: pass scale=1f and prefersReduced=false → Full.
 */
fun resolveMotionPolicy(
    animatorScale: Float = 1f,
    prefersReducedMotion: Boolean = false,
): MotionPolicy {
    val fromScale = motionPolicyFromAnimatorScale(animatorScale)
    val fromFlag = motionPolicyFromReduceMotionFlag(prefersReducedMotion)
    return minPolicy(fromScale, fromFlag)
}

/** Stricter of two policies (Off < Reduced < Full). */
fun minPolicy(a: MotionPolicy, b: MotionPolicy): MotionPolicy {
    val order = listOf(MotionPolicy.Off, MotionPolicy.Reduced, MotionPolicy.Full)
    return if (order.indexOf(a) <= order.indexOf(b)) a else b
}

object MotionPolicyDefaults {
    const val ReducedScale: Float = 0.4f
}
