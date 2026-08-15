package me.rosuh.easywatermark.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.updateTransition
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.IntOffset
import me.rosuh.easywatermark.ui.theme.EwmTheme
import me.rosuh.easywatermark.ui.theme.MotionPolicy
import me.rosuh.easywatermark.ui.theme.currentMotionPolicy
import me.rosuh.easywatermark.ui.theme.editorChromeColor
import me.rosuh.easywatermark.ui.theme.motionDurationMs

/**
 * True while About is on screen **including** its enter/exit, or while
 * Launch↔Editor [AnimatedContent] is running. Decorative mesh must not run
 * Offscreen in those windows (dual-layer hitch).
 */
val LocalShellObscured = staticCompositionLocalOf { false }

/**
 * Shared product-shell navigator for Launch / Editor / About.
 * Platforms keep Activity/window containers only; route transitions live here.
 * I3: durations honor [currentMotionPolicy] (0 = instant under Off).
 *
 * Launch↔Editor uses short horizontal slide + fade (not production LaunchView spring morph) —
 * intentional product route transition per ADR-0023.
 *
 * About is an **overlay** on the live Launch/Editor tree (Activity-stack semantics).
 * [AnimatedContent] must not dispose the under screen or About→Launch remounts
 * BrandLogo Offscreen mesh mid-pop. The under [graphicsLayer] is About-cover
 * only — do not wrap Launch↔Editor in a parent layer.
 *
 * @param aboutReturn Screen under About (Launch or Editor). Session
 * [LaunchScreenState.aboutReturnUiState] mapped with [ProductShellNav.routeFromLaunchUi].
 * @param chromeColor Optional letterbox fill. Desktop passes window chrome so title band and body
 * share one source under content editor theme (ADR-0027 option B). Null → [editorChromeColor].
 */
@Composable
fun ProductShellHost(
    route: ProductShellNav.Route,
    modifier: Modifier = Modifier,
    chromeColor: Color? = null,
    aboutReturn: ProductShellNav.Route = ProductShellNav.Route.Launch,
    content: @Composable (route: ProductShellNav.Route) -> Unit,
) {
    val motionPolicy = currentMotionPolicy()
    // Outer Box owns the product chrome fill. About enter/exit uses scaleIn/Out; the letterbox
    // around scaled pages must never show Compose/Desktop default white (owner recording
    // 2026-08-10). Prefer explicit [chromeColor] (Desktop window chrome) or ambient scheme
    // via editorChromeColor() — never a hard-coded olive that fights photo theme.
    val chrome = chromeColor ?: editorChromeColor()
    val baseRoute = ProductShellNav.overlayBase(route, aboutReturn)
    val showAbout = route == ProductShellNav.Route.About
    val aboutCover = updateTransition(showAbout, label = "aboutOverlay")
    val aboutPresent = aboutCover.currentState || aboutCover.targetState
    // One transition drives both the swap and [LocalShellObscured]. A parent
    // graphicsLayer around this tree is About-only: Launch↔Editor already has
    // its own slide layers, and a full-screen parent layer forces both pages
    // through one offscreen pass every frame (Launch→Editor hitch after the
    // About overlay landed).
    val baseTransition = updateTransition(baseRoute, label = "productShellBase")
    val baseBusy = baseTransition.currentState != baseTransition.targetState
    val underScale = aboutCover.animateFloat(
        transitionSpec = { ProductShellTransitions.mediumFloatSpec(motionPolicy) },
        label = "underScale",
    ) { covered ->
        if (covered) ProductShellTransitions.UnderCoveredScale else 1f
    }
    val underSlide = aboutCover.animateFloat(
        transitionSpec = { ProductShellTransitions.mediumFloatSpec(motionPolicy) },
        label = "underSlide",
    ) { covered ->
        if (covered) ProductShellTransitions.UnderCoveredSlideFraction else 0f
    }
    val underLayer = if (aboutPresent) {
        Modifier.graphicsLayer {
            val scale = underScale.value
            scaleX = scale
            scaleY = scale
            translationX = underSlide.value * size.width
        }
    } else {
        Modifier
    }
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(chrome),
    ) {
        CompositionLocalProvider(LocalShellObscured provides (aboutPresent || baseBusy)) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .then(underLayer),
            ) {
                baseTransition.AnimatedContent(
                    transitionSpec = {
                        ProductShellTransitions.transform(initialState, targetState, motionPolicy)
                    },
                    contentAlignment = Alignment.Center,
                    modifier = Modifier.fillMaxSize(),
                ) { target ->
                    content(target)
                }
            }
        }
        if (aboutPresent) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .pointerInput(Unit) {
                        awaitPointerEventScope {
                            while (true) {
                                val event = awaitPointerEvent()
                                event.changes.forEach { it.consume() }
                            }
                        }
                    },
            )
        }
        aboutCover.AnimatedVisibility(
            visible = { it },
            enter = ProductShellTransitions.aboutEnter(motionPolicy),
            exit = ProductShellTransitions.aboutExit(motionPolicy),
            modifier = Modifier.fillMaxSize(),
        ) {
            content(ProductShellNav.Route.About)
        }
    }
}

/**
 * About enter/exit mirrors production Activity transitions
 * (`activity_open_in/out`, `activity_close_in/out`).
 *
 * About is drawn in a sibling overlay, so z-index on [ContentTransform] is no longer
 * load-bearing for About. Launch↔Editor still uses [transform].
 */
object ProductShellTransitions {
    /** Covered Launch/Editor rest scale (production `activity_open_out`). */
    const val UnderCoveredScale = 0.5f

    /** Covered Launch/Editor rest x-fraction (production `activity_open_out`). */
    const val UnderCoveredSlideFraction = -0.15f

    internal fun mediumFloatSpec(policy: MotionPolicy) = tween<Float>(
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

    fun aboutEnter(policy: MotionPolicy = MotionPolicy.Full): EnterTransition =
        slideInHorizontally(animationSpec = mediumOffset(policy)) { full -> full } +
            scaleIn(initialScale = 0.75f, animationSpec = mediumFloatSpec(policy))

    fun aboutExit(policy: MotionPolicy = MotionPolicy.Full): ExitTransition =
        slideOutHorizontally(animationSpec = mediumOffset(policy)) { full -> full } +
            scaleOut(targetScale = 0.75f, animationSpec = mediumFloatSpec(policy))

    fun transform(
        initialState: ProductShellNav.Route,
        targetState: ProductShellNav.Route,
        motionPolicy: MotionPolicy = MotionPolicy.Full,
    ): ContentTransform {
        val mediumFloat = mediumFloatSpec(motionPolicy)
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
            // Kept for tests / accidental About in the base AnimatedContent.
            toAbout -> {
                contentTransform(
                    enter = aboutEnter(motionPolicy),
                    exit = slideOutHorizontally(animationSpec = mediumOffset) { full ->
                        (full * UnderCoveredSlideFraction).toInt()
                    } + scaleOut(targetScale = UnderCoveredScale, animationSpec = mediumFloat),
                    targetZ = 1f,
                )
            }
            fromAbout -> {
                contentTransform(
                    enter = slideInHorizontally(animationSpec = mediumOffset) { full ->
                        (full * UnderCoveredSlideFraction).toInt()
                    } + scaleIn(initialScale = UnderCoveredScale, animationSpec = mediumFloat),
                    exit = aboutExit(motionPolicy),
                    targetZ = 0f,
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
        enter: EnterTransition,
        exit: ExitTransition,
        targetZ: Float,
    ): ContentTransform =
        ContentTransform(
            targetContentEnter = enter,
            initialContentExit = exit,
            targetContentZIndex = targetZ,
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
