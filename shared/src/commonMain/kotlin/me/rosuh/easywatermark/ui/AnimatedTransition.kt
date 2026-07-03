package me.rosuh.easywatermark.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.AnimatedVisibilityScope
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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

const val ANIMATION_DURATION = 50L

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
    val animateTrigger = remember {
        mutableStateOf(false)
    }
    val onDismissSharedFlow: MutableSharedFlow<Any> = remember { MutableSharedFlow() }
    val coroutineScope: CoroutineScope = rememberCoroutineScope()
    LaunchedEffect(Unit) {
        launch {
            delay(ANIMATION_DURATION)
            animateTrigger.value = true
        }
        launch {
            onDismissSharedFlow.asSharedFlow().collectLatest {
                startDismissWithExitAnimation(animateTrigger, onDismissRequest)
            }
        }
    }
    val scope = rememberCoroutineScope()
    val dismissWithAnimation: () -> Unit = {
        scope.launch {
            startDismissWithExitAnimation(
                animateTrigger = animateTrigger,
                onDismiss = {
                    onDismissRequest()
                }
            )
        }
    }
    backHandler(dismissWithAnimation)
    Box(contentAlignment = contentAlignment, modifier = Modifier.fillMaxSize()) {
        AnimatedSlideInTransition(visible = animateTrigger.value) {
            content(AnimatedTransitionDialogHelper(coroutineScope, onDismissSharedFlow))
        }
    }
}

@Composable
fun AnimatedSlideInTransition(
    visible: Boolean,
    content: @Composable AnimatedVisibilityScope.() -> Unit,
) {
    AnimatedVisibility(
        visible = visible,
        enter = slideInVertically(initialOffsetY = { it }) + fadeIn(),
        exit = slideOutVertically(targetOffsetY = { it }) + fadeOut(),
        content = content
    )
}

suspend fun startDismissWithExitAnimation(
    animateTrigger: MutableState<Boolean>,
    onDismiss: () -> Unit,
) {
    animateTrigger.value = false
    delay(300)
    onDismiss()
}
