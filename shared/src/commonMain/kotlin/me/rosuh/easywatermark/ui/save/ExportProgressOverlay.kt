package me.rosuh.easywatermark.ui.save

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import me.rosuh.easywatermark.data.model.JobState
import me.rosuh.easywatermark.shared.generated.resources.Res
import me.rosuh.easywatermark.shared.generated.resources.ic_save_done
import me.rosuh.easywatermark.ui.theme.EwmTheme
import me.rosuh.easywatermark.ui.theme.MotionPolicy
import me.rosuh.easywatermark.ui.theme.currentMotionPolicy
import me.rosuh.easywatermark.ui.theme.md_theme_dark_tertiary
import me.rosuh.easywatermark.ui.theme.motionDurationMs
import org.jetbrains.compose.resources.painterResource

/** Coarse overlay phase used by production and tests. */
internal enum class ExportOverlayPhase {
    Ready,
    Ing,
    Success,
    Failure,
}

/** How the success icon should present for a phase transition. */
internal enum class SuccessIconMotion {
    /** Live Ing → Success: run fade+scale after wipe reaches full. */
    AnimateEntrance,

    /** Restored / recycled Success: final icon immediately, no replay. */
    SnapFinal,

    /** Non-success phases. */
    Hide,
}

/** Full-motion duration for the live success-icon entrance (fade + scale). */
internal const val ExportSuccessIconMs: Int = 180

/** Start scale for the live success-icon entrance — near final size, ease-out arrival. */
internal const val ExportSuccessIconStartScale: Float = 0.94f

internal fun exportOverlayPhase(jobState: JobState): ExportOverlayPhase = when (jobState) {
    JobState.Ready -> ExportOverlayPhase.Ready
    JobState.Ing -> ExportOverlayPhase.Ing
    is JobState.Success -> ExportOverlayPhase.Success
    is JobState.Failure -> ExportOverlayPhase.Failure
}

/**
 * Production success-icon decision. Called by [ExportProgressOverlay] on every phase entry.
 *
 * - Live [ExportOverlayPhase.Ing] → [ExportOverlayPhase.Success] → [SuccessIconMotion.AnimateEntrance]
 * - Already-[ExportOverlayPhase.Success] (recycle) or never-saw-Ing → [SuccessIconMotion.SnapFinal]
 * - Else → [SuccessIconMotion.Hide]
 */
internal fun resolveSuccessIconMotion(
    previous: ExportOverlayPhase,
    current: ExportOverlayPhase,
): SuccessIconMotion {
    if (current != ExportOverlayPhase.Success) return SuccessIconMotion.Hide
    return when (previous) {
        ExportOverlayPhase.Ing -> SuccessIconMotion.AnimateEntrance
        ExportOverlayPhase.Success -> SuccessIconMotion.SnapFinal
        ExportOverlayPhase.Ready, ExportOverlayPhase.Failure -> SuccessIconMotion.SnapFinal
    }
}

/** MotionPolicy-scaled icon duration; 0 under Off. */
internal fun exportSuccessIconDurationMs(policy: MotionPolicy): Int =
    motionDurationMs(policy, ExportSuccessIconMs)

/**
 * Production [ProgressImageView] + [ic_save_done] analogue.
 *
 * - Ing: left→right wipe to ~25% (start())
 * - Success: wipe to 100% then centered white check with a short fade+scale entrance
 * - Failure: full error wash
 * - Recomposition / waterfall recycle: **does not re-play** — Success snaps full, Ing keeps progress
 *
 * Wash color = Material tertiary @ ~50% (production `colorTertiary` + alpha 125), not brand yellow.
 */
@Composable
fun ExportProgressOverlay(
    jobState: JobState,
    modifier: Modifier = Modifier,
    successIcon: Painter = painterResource(Res.drawable.ic_save_done),
    content: @Composable BoxScope.() -> Unit,
) {
    val phase = exportOverlayPhase(jobState)

    val progress = remember { Animatable(0f) }
    val checkAlpha = remember { Animatable(0f) }
    val checkScale = remember { Animatable(ExportSuccessIconStartScale) }
    // Survives only while this item stays composed; on recycle we snap without re-playing.
    var lastPhase by remember { mutableStateOf(ExportOverlayPhase.Ready) }
    var showCheck by remember { mutableStateOf(false) }
    // I3: honor MotionPolicy (0ms → snap via animateTo with empty duration).
    val policy = currentMotionPolicy()
    val wipeMs = motionDurationMs(policy, EwmTheme.motion.exportWipeMs)
    val iconMs = exportSuccessIconDurationMs(policy)

    LaunchedEffect(phase, wipeMs, iconMs) {
        when (phase) {
            ExportOverlayPhase.Ready -> {
                progress.snapTo(0f)
                checkAlpha.snapTo(0f)
                checkScale.snapTo(ExportSuccessIconStartScale)
                showCheck = false
                lastPhase = ExportOverlayPhase.Ready
            }
            ExportOverlayPhase.Ing -> {
                showCheck = false
                checkAlpha.snapTo(0f)
                checkScale.snapTo(ExportSuccessIconStartScale)
                if (lastPhase == ExportOverlayPhase.Ing && progress.value >= 0.2f) {
                    // Already running / recycled mid-export — hold ~25%, do not restart wipe.
                    progress.snapTo(progress.value.coerceAtLeast(0.25f).coerceAtMost(0.28f))
                } else {
                    progress.snapTo(0f)
                    // Production start(): 0 → 0.25
                    if (wipeMs <= 0) {
                        progress.snapTo(0.25f)
                    } else {
                        progress.animateTo(
                            0.25f,
                            animationSpec = tween(durationMillis = wipeMs, easing = LinearEasing),
                        )
                    }
                }
                lastPhase = ExportOverlayPhase.Ing
            }
            ExportOverlayPhase.Success -> {
                when (resolveSuccessIconMotion(lastPhase, phase)) {
                    SuccessIconMotion.SnapFinal -> {
                        // Recycle after success or restored finished list: final state, no re-animation.
                        progress.snapTo(1f)
                        checkAlpha.snapTo(1f)
                        checkScale.snapTo(1f)
                        showCheck = true
                    }
                    SuccessIconMotion.AnimateEntrance -> {
                        val from = progress.value.coerceAtLeast(0.25f)
                        progress.snapTo(from)
                        if (wipeMs <= 0) {
                            progress.snapTo(1f)
                        } else {
                            progress.animateTo(
                                1f,
                                animationSpec = tween(durationMillis = wipeMs, easing = LinearEasing),
                            )
                        }
                        // Live completion: check enters only after the wipe reaches full.
                        showCheck = true
                        if (iconMs <= 0) {
                            checkAlpha.snapTo(1f)
                            checkScale.snapTo(1f)
                        } else {
                            checkAlpha.snapTo(0f)
                            checkScale.snapTo(ExportSuccessIconStartScale)
                            val spec = tween<Float>(
                                durationMillis = iconMs,
                                easing = LinearOutSlowInEasing,
                            )
                            launch { checkAlpha.animateTo(1f, animationSpec = spec) }
                            launch { checkScale.animateTo(1f, animationSpec = spec) }
                        }
                    }
                    SuccessIconMotion.Hide -> {
                        progress.snapTo(1f)
                        checkAlpha.snapTo(0f)
                        checkScale.snapTo(ExportSuccessIconStartScale)
                        showCheck = false
                    }
                }
                lastPhase = ExportOverlayPhase.Success
            }
            ExportOverlayPhase.Failure -> {
                progress.snapTo(1f)
                checkAlpha.snapTo(0f)
                checkScale.snapTo(ExportSuccessIconStartScale)
                showCheck = false
                lastPhase = ExportOverlayPhase.Failure
            }
        }
    }

    // Production: compositeARGBWithAlpha(colorTertiary, 125) ≈ tertiary @ 49%
    val successWash = md_theme_dark_tertiary.copy(alpha = 125f / 255f)
    val failWash = MaterialTheme.colorScheme.error.copy(alpha = 0.55f)
    val wash = when (phase) {
        ExportOverlayPhase.Failure -> failWash
        ExportOverlayPhase.Ing, ExportOverlayPhase.Success -> successWash
        ExportOverlayPhase.Ready -> Color.Transparent
    }

    Box(modifier = modifier) {
        content()
        if (phase != ExportOverlayPhase.Ready && progress.value > 0f) {
            val fraction = progress.value
            Canvas(Modifier.fillMaxSize()) {
                drawRect(
                    color = wash,
                    topLeft = Offset.Zero,
                    size = Size(width = size.width * fraction, height = size.height),
                )
            }
        }
        // Production iv_done: centered check on success (white stroke icon).
        if (showCheck && phase == ExportOverlayPhase.Success) {
            Icon(
                painter = successIcon,
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier
                    .align(Alignment.Center)
                    .size(28.dp)
                    .graphicsLayer {
                        alpha = checkAlpha.value
                        scaleX = checkScale.value
                        scaleY = checkScale.value
                    },
            )
        }
    }
}
