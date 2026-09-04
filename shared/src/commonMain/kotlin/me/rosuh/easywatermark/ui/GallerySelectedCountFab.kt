package me.rosuh.easywatermark.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import me.rosuh.easywatermark.ui.theme.EwmTheme
import me.rosuh.easywatermark.ui.theme.currentMotionPolicy
import me.rosuh.easywatermark.ui.theme.motionDurationMs

@Composable
fun GallerySelectedCountFab(
    selectedCount: Int,
    icon: Painter,
    contentDescription: String,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val motionPolicy = currentMotionPolicy()
    val ms = motionDurationMs(motionPolicy, EwmTheme.motion.contentSizeMs)
    val floatSpec = tween<Float>(durationMillis = ms, easing = FastOutSlowInEasing)
    val offsetSpec = tween<IntOffset>(durationMillis = ms, easing = FastOutSlowInEasing)

    AnimatedVisibility(
        visible = selectedCount > 0,
        enter = slideInVertically(
            animationSpec = offsetSpec,
            initialOffsetY = { fullHeight -> fullHeight },
        ) + fadeIn(animationSpec = floatSpec),
        exit = slideOutVertically(
            animationSpec = offsetSpec,
            targetOffsetY = { fullHeight -> fullHeight },
        ) + fadeOut(animationSpec = floatSpec),
        modifier = modifier,
    ) {
        ExtendedFloatingActionButton(
            modifier = Modifier
                .padding(64.dp)
                .testTag("sharedComposeGalleryConfirm"),
            onClick = onClick,
        ) {
            Icon(
                painter = icon,
                contentDescription = contentDescription,
            )
            Text(text = "$selectedCount")
        }
    }
}
