package me.rosuh.easywatermark.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.IntOffset
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import me.rosuh.easywatermark.ui.theme.EwmTheme
import me.rosuh.easywatermark.ui.theme.currentMotionPolicy
import me.rosuh.easywatermark.ui.theme.motionDurationMs

class AnimatedTransitionDialogHelper(
    private val coroutineScope: CoroutineScope,
    private val onDismissFlow: MutableSharedFlow<Any>,
) {
    fun triggerDismiss() {
        coroutineScope.launch {
            onDismissFlow.emit(Any())
        }
    }
}

@Composable
fun AnimatedTransitionHost(
    onDismissRequest: () -> Unit,
    contentAlignment: Alignment = Alignment.Center,
    backHandler: @Composable (onBack: () -> Unit) -> Unit = {},
    content: @Composable (AnimatedTransitionDialogHelper) -> Unit,
) {
    val motionPolicy = currentMotionPolicy()
    val enterDelayMs = motionDurationMs(motionPolicy, EwmTheme.motion.dialogHostEnterDelayMs)
    val exitMs = motionDurationMs(motionPolicy, EwmTheme.motion.dialogHostExitMs)
    val slideFadeMs = motionDurationMs(motionPolicy, EwmTheme.motion.shellShortMs)

    val animateTrigger = remember {
        mutableStateOf(false)
    }
    val onDismissSharedFlow: MutableSharedFlow<Any> = remember { MutableSharedFlow() }
    val coroutineScope: CoroutineScope = rememberCoroutineScope()
    LaunchedEffect(enterDelayMs) {
        launch {
            delay(enterDelayMs.toLong())
            animateTrigger.value = true
        }
        launch {
            onDismissSharedFlow.asSharedFlow().collectLatest {
                startDismissWithExitAnimation(animateTrigger, exitMs, onDismissRequest)
            }
        }
    }
    val scope = rememberCoroutineScope()
    val dismissWithAnimation: () -> Unit = {
        scope.launch {
            startDismissWithExitAnimation(
                animateTrigger = animateTrigger,
                exitMs = exitMs,
                onDismiss = {
                    onDismissRequest()
                },
            )
        }
    }
    backHandler(dismissWithAnimation)
    Box(contentAlignment = contentAlignment, modifier = Modifier.fillMaxSize()) {
        AnimatedSlideInTransition(
            visible = animateTrigger.value,
            durationMs = slideFadeMs,
        ) {
            content(AnimatedTransitionDialogHelper(coroutineScope, onDismissSharedFlow))
        }
    }
}

@Composable
private fun AnimatedSlideInTransition(
    visible: Boolean,
    durationMs: Int,
    content: @Composable AnimatedVisibilityScope.() -> Unit,
) {
    val floatSpec = tween<Float>(durationMillis = durationMs, easing = FastOutSlowInEasing)
    val offsetSpec = tween<IntOffset>(durationMillis = durationMs, easing = FastOutSlowInEasing)
    AnimatedVisibility(
        visible = visible,
        enter = slideInVertically(
            animationSpec = offsetSpec,
            initialOffsetY = { it },
        ) + fadeIn(animationSpec = floatSpec),
        exit = slideOutVertically(
            animationSpec = offsetSpec,
            targetOffsetY = { it },
        ) + fadeOut(animationSpec = floatSpec),
        content = content,
    )
}

private suspend fun startDismissWithExitAnimation(
    animateTrigger: MutableState<Boolean>,
    exitMs: Int,
    onDismiss: () -> Unit,
) {
    animateTrigger.value = false
    delay(exitMs.toLong().coerceAtLeast(0L))
    onDismiss()
}
