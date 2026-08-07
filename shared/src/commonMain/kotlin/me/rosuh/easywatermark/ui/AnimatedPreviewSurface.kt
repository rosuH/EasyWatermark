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
import me.rosuh.easywatermark.ui.theme.previewCrossfadeDurationMs

/**
 * Shared CMP preview surface motion (M2 crossfade + M7 first reveal).
 *
 * Hosts pass a stable [contentKey] (source path / uri). When the key changes and content is
 * ready, the new layer crossfades in over the previous (aspect-agnostic alpha blend — hosts that
 * need bounds morph keep their own Canvas path, e.g. Android [WaterMarkCanvas]).
 *
 * First non-null content fades 0→1 ([EwmTheme.motion.firstPreviewRevealMs]).
 * All durations honor [currentMotionPolicy] (0 = snap).
 *
 * [content] is always the **current** ready frame; previous frame is held only as a snapshot
 * of the last composed content via [displayedKey] identity — callers should keep prior bitmap
 * until the next ready frame replaces it (typical host pattern).
 */
@Composable
fun AnimatedPreviewSurface(
    contentKey: String?,
    hasContent: Boolean,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val policy = currentMotionPolicy()
    val revealMs = motionDurationMs(policy, EwmTheme.motion.firstPreviewRevealMs)
    val crossfadeMs = previewCrossfadeDurationMs(policy, aspectDelta = 0.35f)

    var displayedKey by remember { mutableStateOf<String?>(null) }
    val alpha = remember { Animatable(0f) }

    LaunchedEffect(contentKey, hasContent, revealMs, crossfadeMs) {
        if (!hasContent || contentKey.isNullOrEmpty()) {
            // Keep last painted frame alpha if we had one; don't force black flash.
            return@LaunchedEffect
        }
        val previous = displayedKey
        when {
            previous == null -> {
                // M7: first reveal
                if (revealMs <= 0) {
                    alpha.snapTo(1f)
                } else {
                    alpha.snapTo(0f)
                    alpha.animateTo(
                        1f,
                        animationSpec = tween(durationMillis = revealMs, easing = FastOutSlowInEasing),
                    )
                }
                displayedKey = contentKey
            }
            previous != contentKey -> {
                // M2: image switch — brief dip then settle (single-layer hosts).
                if (crossfadeMs <= 0) {
                    alpha.snapTo(1f)
                } else {
                    alpha.snapTo(0.15f)
                    alpha.animateTo(
                        1f,
                        animationSpec = tween(
                            durationMillis = crossfadeMs,
                            easing = FastOutSlowInEasing,
                        ),
                    )
                }
                displayedKey = contentKey
            }
            else -> {
                // Same key refresh (watermark config) — ensure fully visible, no re-reveal.
                if (alpha.value < 1f) {
                    if (revealMs <= 0) alpha.snapTo(1f) else alpha.animateTo(1f, tween(revealMs / 2))
                }
            }
        }
    }

    Box(
        modifier = modifier.graphicsLayer { this.alpha = alpha.value },
        contentAlignment = Alignment.Center,
    ) {
        if (hasContent) {
            content()
        }
    }
}
