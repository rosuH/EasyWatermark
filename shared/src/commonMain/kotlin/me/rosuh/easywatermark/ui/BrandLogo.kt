package me.rosuh.easywatermark.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import kotlinx.coroutines.delay
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
    val obscured = LocalShellObscured.current
    // First paint stays static so cold Launch/About open does not pay Offscreen+mesh
    // setup on the same frame as first resource decode. Do not key this on [obscured]:
    // About overlay must not reset meshReady or return remounts Offscreen mid-pop.
    var meshReady by remember { mutableStateOf(false) }
    LaunchedEffect(animate, motionOk) {
        if (!animate || !motionOk) {
            meshReady = false
            StartupTrace.markOnce("mesh_skipped")
            return@LaunchedEffect
        }
        withFrameNanos { }
        withFrameNanos { }
        meshReady = true
        StartupTrace.markOnce("mesh_ready")
    }
    val effectiveAnimate = animate && motionOk && meshReady && !obscured
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
    // 80ms ticks: same reverse 1→0.1 sweep, without a 60fps Compose clock (emulator idle CPU).
    val pos = remember { mutableFloatStateOf(1f) }
    LaunchedEffect(sweepMs) {
        val frameMs = 80L
        var elapsed = 0L
        val period = 2L * sweepMs
        while (true) {
            val cycle = (elapsed % period).toFloat()
            val half = sweepMs.toFloat()
            val t = if (cycle <= half) cycle / half else 2f - cycle / half
            pos.floatValue = 1f - 0.9f * t
            delay(frameMs)
            elapsed += frameMs
        }
    }
    val p = pos.floatValue

    val meshPainter = remember(p) {
        MeshGradientPainter(rows = 2, columns = 2, hasBicubicColor = true) {
            // Large field travel so the wash is as readable as the old linear sweep.
            // pos=1 → colors biased top-left/amber; pos=0.1 → pull toward cyan bottom-right.
            val ox = (1.1f - p) * 0.9f - 0.45f
            val oy = p * 0.65f - 0.25f
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
                // Read pos in the layer so draw invalidates without recomposing the tree.
                .graphicsLayer {
                    blendMode = BlendMode.SrcAtop
                    translationX = p * 0.001f
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
