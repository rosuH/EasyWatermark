package me.rosuh.easywatermark.ui.save

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
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
import me.rosuh.easywatermark.data.model.JobState
import me.rosuh.easywatermark.shared.generated.resources.Res
import me.rosuh.easywatermark.shared.generated.resources.ic_save_done
import me.rosuh.easywatermark.ui.theme.EwmTheme
import me.rosuh.easywatermark.ui.theme.MotionPolicy
import me.rosuh.easywatermark.ui.theme.currentMotionPolicy
import me.rosuh.easywatermark.ui.theme.md_theme_dark_tertiary
import me.rosuh.easywatermark.ui.theme.motionDurationMs
import org.jetbrains.compose.resources.painterResource

/**
 * Production [ProgressImageView] + [ic_save_done] analogue.
 *
 * - Ing: left→right wipe to ~25% (start())
 * - Success: wipe to 100% then centered white check (finish() + iv_done)
 * - Failure: full error wash
 * - Recomposition / LazyRow recycle: **does not re-play** — Success snaps full, Ing keeps progress
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
    // Coarse phase so Success/Success recompose (new instance) does not re-trigger animation.
    val phase = when (jobState) {
        JobState.Ready -> Phase.Ready
        JobState.Ing -> Phase.Ing
        is JobState.Success -> Phase.Success
        is JobState.Failure -> Phase.Failure
    }

    val progress = remember { Animatable(0f) }
    // M6: prod ivDone.appear() — scale 0.75→1 + alpha; snap on recycle / Off.
    val checkAppear = remember { Animatable(0f) }
    // Survives only while this item stays composed; on recycle we snap without re-playing.
    var lastPhase by remember { mutableStateOf(Phase.Ready) }
    var showCheck by remember { mutableStateOf(false) }
    val motionPolicy = currentMotionPolicy()
    // I3: honor MotionPolicy (0ms → snap via animateTo with empty duration).
    val wipeMs = motionDurationMs(motionPolicy, EwmTheme.motion.exportWipeMs)
    val checkMs = motionDurationMs(motionPolicy, EwmTheme.motion.exportCheckAppearMs)

    suspend fun playCheckAppear(replay: Boolean) {
        if (!replay) {
            checkAppear.snapTo(1f)
            return
        }
        if (checkMs <= 0) {
            checkAppear.snapTo(1f)
            return
        }
        checkAppear.snapTo(0f)
        if (motionPolicy == MotionPolicy.Full) {
            checkAppear.animateTo(
                1f,
                animationSpec = spring(
                    dampingRatio = Spring.DampingRatioMediumBouncy,
                    stiffness = Spring.StiffnessMedium,
                ),
            )
        } else {
            checkAppear.animateTo(
                1f,
                animationSpec = tween(durationMillis = checkMs, easing = FastOutSlowInEasing),
            )
        }
    }

    LaunchedEffect(phase, wipeMs, checkMs) {
        when (phase) {
            Phase.Ready -> {
                progress.snapTo(0f)
                checkAppear.snapTo(0f)
                showCheck = false
                lastPhase = Phase.Ready
            }
            Phase.Ing -> {
                showCheck = false
                checkAppear.snapTo(0f)
                if (lastPhase == Phase.Ing && progress.value >= 0.2f) {
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
                lastPhase = Phase.Ing
            }
            Phase.Success -> {
                when (lastPhase) {
                    Phase.Success -> {
                        // LazyRow recycle after success: show final state, no re-animation.
                        progress.snapTo(1f)
                        showCheck = true
                        playCheckAppear(replay = false)
                    }
                    Phase.Ing -> {
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
                        showCheck = true
                        playCheckAppear(replay = true)
                    }
                    else -> {
                        // Never saw Ing (e.g. restored finished list): full wash + check, no wipe.
                        progress.snapTo(1f)
                        showCheck = true
                        playCheckAppear(replay = true)
                    }
                }
                lastPhase = Phase.Success
            }
            Phase.Failure -> {
                progress.snapTo(1f)
                checkAppear.snapTo(0f)
                showCheck = false
                lastPhase = Phase.Failure
            }
        }
    }

    // Production: compositeARGBWithAlpha(colorTertiary, 125) ≈ tertiary @ 49%
    val successWash = md_theme_dark_tertiary.copy(alpha = 125f / 255f)
    val failWash = MaterialTheme.colorScheme.error.copy(alpha = 0.55f)
    val wash = when (phase) {
        Phase.Failure -> failWash
        Phase.Ing, Phase.Success -> successWash
        Phase.Ready -> Color.Transparent
    }

    Box(modifier = modifier) {
        content()
        if (phase != Phase.Ready && progress.value > 0f) {
            val fraction = progress.value
            Canvas(Modifier.fillMaxSize()) {
                drawRect(
                    color = wash,
                    topLeft = Offset.Zero,
                    size = Size(width = size.width * fraction, height = size.height),
                )
            }
        }
        // Production iv_done.appear(): centered check with scale/alpha (M6).
        if (showCheck && phase == Phase.Success) {
            val t = checkAppear.value.coerceIn(0f, 1f)
            Icon(
                painter = successIcon,
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier
                    .align(Alignment.Center)
                    .size(28.dp)
                    .graphicsLayer {
                        val s = 0.75f + 0.25f * t
                        scaleX = s
                        scaleY = s
                        alpha = t
                    },
            )
        }
    }
}

private enum class Phase {
    Ready,
    Ing,
    Success,
    Failure,
}
