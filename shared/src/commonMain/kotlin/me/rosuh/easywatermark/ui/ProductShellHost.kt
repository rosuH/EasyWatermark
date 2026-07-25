package me.rosuh.easywatermark.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.IntOffset
import me.rosuh.easywatermark.ui.theme.EwmTheme
import me.rosuh.easywatermark.ui.theme.MotionPolicy
import me.rosuh.easywatermark.ui.theme.currentMotionPolicy
import me.rosuh.easywatermark.ui.theme.motionDurationMs

/**
 * Shared product-shell navigator for Launch / Editor / About.
 * Platforms keep Activity/window containers only; route transitions live here.
 * I3: durations honor [currentMotionPolicy] (0 = instant under Off).
 */
@Composable
fun ProductShellHost(
    route: ProductShellNav.Route,
    modifier: Modifier = Modifier,
    content: @Composable (route: ProductShellNav.Route) -> Unit,
) {
    val motionPolicy = currentMotionPolicy()
    AnimatedContent(
        targetState = route,
        transitionSpec = {
            ProductShellTransitions.transform(initialState, targetState, motionPolicy)
        },
        contentAlignment = Alignment.Center,
        label = "productShellRoute",
        // No clip — scale/slide of About must not be cut by the shell bounds.
        modifier = modifier.fillMaxSize(),
    ) { target ->
        content(target)
    }
}

/**
 * About enter/exit mirrors production Activity transitions
 * (`activity_open_in/out`, `activity_close_in/out`).
 *
 * **Z-order (critical):** production keeps About *on top* while it slides out.
 * [ContentTransform.targetContentZIndex] must put About above Launch/Editor both
 * On enter (target z=1) and on exit (target Launch/Editor z=0 so prior About z=1 wins). * Without this, exit draws home on top of About and looks like a hard cover-up.
 */
object ProductShellTransitions {
    /** About sits above Launch/Editor during open *and* close. */
    private const val AboutZ = 1f

    /** Screens under About. */
    private const val UnderAboutZ = 0f

    private fun mediumFloat(policy: MotionPolicy) = tween<Float>(
        durationMillis = motionDurationMs(policy, EwmTheme.motion.shellMediumMs),
        easing = FastOutSlowInEasing,
    )

    private fun mediumOffset(policy: MotionPolicy) = tween<IntOffset>(
        durationMillis = motionDurationMs(policy, EwmTheme.motion.shellMediumMs),
        easing = FastOutSlowInEasing,
    )

    private fun shortFloat(policy: MotionPolicy) = tween<Float>(
        durationMillis = motionDurationMs(policy, EwmTheme.motion.shellShortMs),
        easing = FastOutSlowInEasing,
    )

    private fun shortOffset(policy: MotionPolicy) = tween<IntOffset>(
        durationMillis = motionDurationMs(policy, EwmTheme.motion.shellShortMs),
        easing = FastOutSlowInEasing,
    )

    fun transform(
        initialState: ProductShellNav.Route,
        targetState: ProductShellNav.Route,
        motionPolicy: MotionPolicy = MotionPolicy.Full,
    ): ContentTransform {
        val mediumFloat = mediumFloat(motionPolicy)
        val mediumOffset = mediumOffset(motionPolicy)
        val shortFloat = shortFloat(motionPolicy)
        val shortOffset = shortOffset(motionPolicy)
        val toAbout = targetState == ProductShellNav.Route.About
        val fromAbout = initialState == ProductShellNav.Route.About
        val toEditor = targetState == ProductShellNav.Route.Editor
        val fromEditor = initialState == ProductShellNav.Route.Editor
        val toLaunch = targetState == ProductShellNav.Route.Launch
        val fromLaunch = initialState == ProductShellNav.Route.Launch
        return when {
            // Production open_in / open_out — About enters on top.
            toAbout -> {
                contentTransform(
                    enter = slideInHorizontally(animationSpec = mediumOffset) { full -> full } +
                        scaleIn(initialScale = 0.75f, animationSpec = mediumFloat),
                    exit = slideOutHorizontally(animationSpec = mediumOffset) { full ->
                        (-full * 0.15f).toInt()
                    } + scaleOut(targetScale = 0.5f, animationSpec = mediumFloat),
                    targetZ = AboutZ,
                )
            }
            // Production close_in / close_out — About (initial, z=1) stays on top while exiting.
            fromAbout -> {
                contentTransform(
                    enter = slideInHorizontally(animationSpec = mediumOffset) { full ->
                        (-full * 0.15f).toInt()
                    } + scaleIn(initialScale = 0.45f, animationSpec = mediumFloat),
                    exit = slideOutHorizontally(animationSpec = mediumOffset) { full -> full } +
                        scaleOut(targetScale = 0.75f, animationSpec = mediumFloat),
                    // Launch/Editor is the target: keep it *under* the exiting About.
                    targetZ = UnderAboutZ,
                )
            }
            fromLaunch && toEditor -> {
                contentTransform(
                    enter = fadeIn(animationSpec = shortFloat) +
                        slideInHorizontally(animationSpec = shortOffset) { full -> full },
                    exit = fadeOut(animationSpec = shortFloat) +
                        slideOutHorizontally(animationSpec = shortOffset) { full ->
                            (-full * 0.12f).toInt()
                        },
                    targetZ = 0f,
                )
            }
            fromEditor && toLaunch -> {
                contentTransform(
                    enter = fadeIn(animationSpec = shortFloat) +
                        slideInHorizontally(animationSpec = shortOffset) { full ->
                            (-full * 0.12f).toInt()
                        },
                    exit = fadeOut(animationSpec = shortFloat) +
                        slideOutHorizontally(animationSpec = shortOffset) { full -> full },
                    targetZ = 0f,
                )
            }
            else -> {
                contentTransform(
                    enter = fadeIn(animationSpec = shortFloat),
                    exit = fadeOut(animationSpec = shortFloat),
                    targetZ = 0f,
                )
            }
        }
    }

    private fun contentTransform(
        enter: androidx.compose.animation.EnterTransition,
        exit: androidx.compose.animation.ExitTransition,
        targetZ: Float,
    ): ContentTransform =
        ContentTransform(
            targetContentEnter = enter,
            initialContentExit = exit,
            targetContentZIndex = targetZ,
            // Do not clip scale/slide; About must stay fully visible while exiting on top.
            sizeTransform = SizeTransform(clip = false),
        )

    fun kind(
        initialState: ProductShellNav.Route,
        targetState: ProductShellNav.Route,
    ): TransitionKind = when {
        targetState == ProductShellNav.Route.About -> TransitionKind.ToAbout
        initialState == ProductShellNav.Route.About -> TransitionKind.FromAbout
        initialState == ProductShellNav.Route.Launch &&
            targetState == ProductShellNav.Route.Editor -> TransitionKind.ToEditor
        initialState == ProductShellNav.Route.Editor &&
            targetState == ProductShellNav.Route.Launch -> TransitionKind.ToLaunch
        else -> TransitionKind.CrossFade
    }

    enum class TransitionKind {
        ToAbout,
        FromAbout,
        ToEditor,
        ToLaunch,
        CrossFade,
    }
}
