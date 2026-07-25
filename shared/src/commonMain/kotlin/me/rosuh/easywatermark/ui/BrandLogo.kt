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
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
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
 * applies production sweeping multi-stop gradient (2.5s reverse infinite).
 * Reduced/Off → static Image (I3 MotionPolicy).
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
 * About-page hero logo (`ic_logo_about_page`). Same gradient animation as launch.
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
 * Shared gradient-mask logo (parity with Android [me.rosuh.easywatermark.ui.widget.ColoredImageVIew]).
 *
 * Gradient stops match production static palette (#FFA51F → #FFD703 → #C0FF39 → #00FFE0).
 * Sweep position animates 1→0.1 over [EwmTheme.motion.logoSweepMs], reverse infinite (Full only).
 */
@Composable
fun GradientMaskedLogo(
    painter: Painter,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    size: Dp = 180.dp,
    animate: Boolean = true,
) {
    // I3: Reduced/Off suppress infinite decorative sweep even if caller passed animate=true.
    val effectiveAnimate = animate && motionAllowsDecorativeLoop(currentMotionPolicy())
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
    val transition = rememberInfiniteTransition(label = "logoGradient")
    val pos by transition.animateFloat(
        initialValue = 1f,
        targetValue = 0.1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = sweepMs, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "logoGradientPos",
    )

    Box(
        modifier = modifier
            .size(size)
            // Offscreen layer so SrcAtop masks the logo alpha (same as Canvas.saveLayer).
            .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
            .drawWithCache {
                val w = this.size.width
                val h = this.size.height
                val brush = Brush.linearGradient(
                    colorStops = LogoGradientStops,
                    start = Offset((1.1f - pos) * w * 2f, pos * h),
                    end = Offset(0f, h),
                )
                onDrawWithContent {
                    drawContent()
                    drawRect(brush = brush, blendMode = BlendMode.SrcAtop)
                }
            },
    ) {
        Image(
            painter = painter,
            contentDescription = contentDescription,
            contentScale = ContentScale.Fit,
            modifier = Modifier.fillMaxSize(),
        )
    }
}

/** Production ColoredImageVIew non-dynamic color stops + positions. */
private val LogoGradientStops: Array<Pair<Float, Color>> = arrayOf(
    0f to Color(0xFFFFA51F),
    0.5f to Color(0xFFFFD703),
    0.7f to Color(0xFFC0FF39),
    0.99f to Color(0xFF00FFE0),
)
