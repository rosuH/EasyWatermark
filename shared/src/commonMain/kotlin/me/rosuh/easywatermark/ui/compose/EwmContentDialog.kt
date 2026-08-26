package me.rosuh.easywatermark.ui.compose

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import me.rosuh.easywatermark.ui.theme.EwmTheme
import me.rosuh.easywatermark.ui.theme.currentMotionPolicy
import me.rosuh.easywatermark.ui.theme.motionDurationMs

/**
 * Centered product dialog for ≥800 surfaces (export / template).
 * Panel chrome matches [EwmModalBottomSheet] / [EwmConfirmDialog].
 *
 * Enter/exit is fade+scale [EwmTheme.motion.contentEnterScale] at
 * [EwmTheme.motion.shellShortMs], scaled by [motionDurationMs]. Dismiss waits
 * for the exit (same pattern as [me.rosuh.easywatermark.ui.AnimatedTransitionHost]).
 */
@Composable
fun EwmContentDialog(
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    maxWidth: Dp = 720.dp,
    maxHeight: Dp = 720.dp,
    properties: DialogProperties = DialogProperties(usePlatformDefaultWidth = false),
    testTag: String = "ewmContentDialog",
    content: @Composable () -> Unit,
) {
    val motionPolicy = currentMotionPolicy()
    val durationMs = motionDurationMs(motionPolicy, EwmTheme.motion.shellShortMs)
    val timed = durationMs > 0
    val spec = tween<Float>(durationMillis = durationMs, easing = FastOutSlowInEasing)
    val scale = EwmTheme.motion.contentEnterScale
    var visible by remember { mutableStateOf(!timed) }
    var dismissing by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val latestOnDismiss by rememberUpdatedState(onDismissRequest)

    LaunchedEffect(timed) {
        if (timed && !dismissing) {
            visible = true
        }
    }

    val dismiss: () -> Unit = {
        if (!dismissing) {
            if (!timed) {
                latestOnDismiss()
            } else {
                dismissing = true
                visible = false
                scope.launch {
                    delay(durationMs.toLong().coerceAtLeast(0L))
                    latestOnDismiss()
                }
            }
        }
    }

    Dialog(
        onDismissRequest = dismiss,
        properties = properties,
    ) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center,
        ) {
            AnimatedVisibility(
                visible = visible,
                enter = fadeIn(spec) + scaleIn(initialScale = scale, animationSpec = spec),
                exit = fadeOut(spec) + scaleOut(targetScale = scale, animationSpec = spec),
            ) {
                Surface(
                    modifier = modifier
                        .widthIn(max = maxWidth)
                        .heightIn(max = maxHeight)
                        .fillMaxWidth()
                        .testTag(testTag),
                    shape = EwmTheme.panel.dialogShape,
                    color = EwmTheme.panel.containerColor,
                    tonalElevation = EwmTheme.panel.tonalElevation,
                ) {
                    // Scroll when content exceeds max height — avoids clipped CTAs on short windows.
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .verticalScroll(rememberScrollState()),
                    ) {
                        content()
                    }
                }
            }
        }
    }
}
