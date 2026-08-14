package me.rosuh.easywatermark.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.paint
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.MeshGradientPainter
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import me.rosuh.easywatermark.ui.theme.EwmTheme
import me.rosuh.easywatermark.ui.theme.currentMotionPolicy
import me.rosuh.easywatermark.ui.theme.motionAllowsDecorativeLoop
import org.jetbrains.compose.resources.painterResource

/**
 * Product launch logo from composeResources [SharedProductDrawables.brandLogo]
 * (`ic_log_transparent`).
 *
 * When [animate] is true **and** [currentMotionPolicy] allows decorative loops (Full),
 * applies a Compose 1.12 [MeshGradientPainter] color wash that sweeps like the former
 * linear gradient (same timing / reverse infinite), masked to logo alpha via Offscreen +
 * SrcAtop. Reduced/Off → static Image.
 */
@Composable
fun BrandLogo(
    modifier: Modifier = Modifier,
    size: Dp = 180.dp,
    animate: Boolean = true,
) {
    GradientMaskedLogo(
        painter = painterResource(SharedProductDrawables.brandLogo),
        contentDescription = "EasyWatermark logo",
        modifier = modifier,
        size = size,
        animate = animate,
    )
}

/**
 * About-page hero logo (`ic_logo_about_page`). Same mesh animation as launch.
 * Default size matches production xxhdpi asset (192px → **64.dp**).
 */
@Composable
fun AboutPageLogo(
    modifier: Modifier = Modifier,
    size: Dp = 64.dp,
    animate: Boolean = true,
) {
    GradientMaskedLogo(
        painter = painterResource(SharedProductDrawables.logoAbout),
        contentDescription = "EasyWatermark",
        modifier = modifier,
        size = size,
        animate = animate,
    )
}

/**
 * Shared mesh-gradient-mask logo (Compose 1.12 [MeshGradientPainter]).
 *
 * Palette matches the former linear ColoredImageVIew stops
 * (#FFA51F / #FFD703 / #C0FF39 / #00FFE0). Sweep phase matches production
 * `pos` 1→0.1 over [EwmTheme.motion.logoSweepMs], reverse infinite (Full only).
 */
@Composable
fun GradientMaskedLogo(
    painter: Painter,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    size: Dp = 180.dp,
    animate: Boolean = true,
) {
    // I3: Reduced/Off suppress infinite decorative mesh even if caller passed animate=true.
    val motionOk = motionAllowsDecorativeLoop(currentMotionPolicy())
    // First paint stays static so About/Launch open does not pay Offscreen+mesh setup
    // on the same frame as route AnimatedContent + first resource decode (cold open jank).
    var meshReady by remember { mutableStateOf(false) }
    LaunchedEffect(animate, motionOk) {
        if (!animate || !motionOk) {
            meshReady = false
            return@LaunchedEffect
        }
        withFrameNanos { }
        withFrameNanos { }
        meshReady = true
    }
    val effectiveAnimate = animate && motionOk && meshReady
    if (!effectiveAnimate) {
        Image(
            painter = painter,
            contentDescription = contentDescription,
            contentScale = ContentScale.Fit,
            modifier = modifier.size(size),
        )
        return
    }

    val sweepMs = EwmTheme.motion.logoSweepMs
    val transition = rememberInfiniteTransition(label = "logoMesh")
    // Same phase as the old linear Brush sweep (1 → 0.1, reverse).
    val pos by transition.animateFloat(
        initialValue = 1f,
        targetValue = 0.1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = sweepMs, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "logoMeshPos",
    )

    // Painter block re-runs in DrawScope each draw and reads [pos]. Keep one instance.
    val meshPainter = remember {
        MeshGradientPainter(rows = 2, columns = 2, hasBicubicColor = true) {
            // Large field travel so the wash is as readable as the old linear sweep.
            // pos=1 → colors biased top-left/amber; pos=0.1 → pull toward cyan bottom-right.
            val ox = (1.1f - pos) * 0.9f - 0.45f
            val oy = pos * 0.65f - 0.25f
            // Row 0
            setVertex(0, 0, Offset(0f, 0f), LogoAmber)
            setVertex(0, 1, Offset((0.5f + ox * 0.55f).coerceIn(0.05f, 0.95f), (0f + oy * 0.35f).coerceIn(0f, 0.45f)), LogoGold)
            setVertex(0, 2, Offset(1f, 0f), LogoLime)
            // Row 1 (interior carries most of the sweep)
            setVertex(1, 0, Offset((0f + oy * 0.25f).coerceIn(0f, 0.35f), (0.5f + ox * 0.2f).coerceIn(0.15f, 0.85f)), LogoGold)
            setVertex(1, 1, Offset((0.4f + ox).coerceIn(0.1f, 0.9f), (0.45f - oy * 0.55f).coerceIn(0.1f, 0.9f)), LogoLime)
            setVertex(1, 2, Offset((1f - oy * 0.2f).coerceIn(0.65f, 1f), (0.5f + ox * 0.25f).coerceIn(0.15f, 0.85f)), LogoCyan)
            // Row 2
            setVertex(2, 0, Offset(0f, 1f), LogoLime)
            setVertex(2, 1, Offset((0.5f - ox * 0.4f).coerceIn(0.05f, 0.95f), 1f), LogoCyan)
            setVertex(2, 2, Offset(1f, 1f), LogoCyan)
        }
    }

    Box(
        modifier = modifier
            .size(size)
            // Offscreen layer so SrcAtop masks the logo alpha (same as Canvas.saveLayer).
            .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen },
    ) {
        Image(
            painter = painter,
            contentDescription = contentDescription,
            contentScale = ContentScale.Fit,
            modifier = Modifier.fillMaxSize(),
        )
        Box(
            modifier = Modifier
                .fillMaxSize()
                // Read [pos] here so the layer invalidates every frame even if Painter
                // snapshot observation misses on a given backend.
                .graphicsLayer {
                    blendMode = BlendMode.SrcAtop
                    // Tiny no-op dependence keeps the read live without visible jitter.
                    translationX = pos * 0.001f
                }
                .paint(meshPainter),
        )
    }
}

/** Production logo palette (former linear ColoredImageVIew stops). */
private val LogoAmber = Color(0xFFFFA51F)
private val LogoGold = Color(0xFFFFD703)
private val LogoLime = Color(0xFFC0FF39)
private val LogoCyan = Color(0xFF00FFE0)
