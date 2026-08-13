package me.rosuh.easywatermark.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import me.rosuh.easywatermark.ui.theme.EwmTheme
import me.rosuh.easywatermark.ui.theme.currentMotionPolicy
import me.rosuh.easywatermark.ui.theme.motionDurationMs
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import me.rosuh.easywatermark.ui.theme.previewCrossfadeDurationMs

/**
 * Shared CMP preview surface motion (M7 first reveal; multi-image switch is hard-cut).
 *
 * Hosts must pass a [contentKey] that identifies the **ready** frame (not a pending selection
 * while still showing the previous bitmap). When key changes ahead of ready content, keep
 * [hasContent]=false (or keep the old key) until the new frame is bound.
 *
 * Snap is expected when:
 * - multi-image switch (product: no switch crossfade — [previewCrossfadeDurationMs] is 0)
 * - [me.rosuh.easywatermark.ui.theme.MotionPolicy.Off] (0ms)
 * - same [contentKey] (watermark config refresh — no re-reveal)
 * - [hasContent] false / null-empty key (hold last frame, no flash)
 *
 * First non-null ready content fades 0→1 ([EwmTheme.motion.firstPreviewRevealMs]).
 */
@Composable
fun AnimatedPreviewSurface(
    contentKey: String?,
    hasContent: Boolean,
    modifier: Modifier = Modifier,
    /** Unused after product hard-cut; kept for call-site ABI. */
    aspectDelta: Float = 0.35f,
    content: @Composable () -> Unit,
) {
    val policy = currentMotionPolicy()
    val revealMs = motionDurationMs(policy, EwmTheme.motion.firstPreviewRevealMs)
    // Product: always hard-cut multi-image switches (function is fixed at 0).
    val crossfadeMs = previewCrossfadeDurationMs(policy, aspectDelta = aspectDelta)

    var displayedKey by remember { mutableStateOf<String?>(null) }
    val alpha = remember { Animatable(0f) }
    val scale = remember { Animatable(1f) }

    LaunchedEffect(contentKey, hasContent, revealMs, crossfadeMs) {
        if (!hasContent || contentKey.isNullOrEmpty()) {
            // Hold last painted frame; do not advance displayedKey until ready content arrives.
            return@LaunchedEffect
        }
        val previous = displayedKey
        when {
            previous == null -> {
                // M7: first reveal
                if (revealMs <= 0) {
                    alpha.snapTo(1f)
                    scale.snapTo(1f)
                } else {
                    alpha.snapTo(0f)
                    scale.snapTo(0.97f)
                    coroutineScope {
                        launch {
                            alpha.animateTo(
                                1f,
                                animationSpec = tween(
                                    durationMillis = revealMs,
                                    easing = FastOutSlowInEasing,
                                ),
                            )
                        }
                        launch {
                            scale.animateTo(
                                1f,
                                animationSpec = tween(
                                    durationMillis = revealMs,
                                    easing = FastOutSlowInEasing,
                                ),
                            )
                        }
                    }
                }
                displayedKey = contentKey
            }
            previous != contentKey -> {
                // Multi-image switch: hard-cut (no alpha dip) so filmstrip focus feels instant.
                alpha.snapTo(1f)
                scale.snapTo(1f)
                displayedKey = contentKey
            }
            else -> {
                // Same key refresh (watermark config) — ensure fully visible, no re-reveal.
                if (alpha.value < 1f) {
                    if (revealMs <= 0) {
                        alpha.snapTo(1f)
                        scale.snapTo(1f)
                    } else {
                        coroutineScope {
                            launch { alpha.animateTo(1f, tween(revealMs / 2)) }
                            launch { scale.animateTo(1f, tween(revealMs / 2)) }
                        }
                    }
                }
            }
        }
    }

    Box(
        modifier = modifier.graphicsLayer {
            this.alpha = alpha.value
            val s = scale.value
            scaleX = s
            scaleY = s
        },
        contentAlignment = Alignment.Center,
    ) {
        if (hasContent) {
            content()
        }
    }
}
