package me.rosuh.easywatermark.ui.about

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.painter.Painter
import me.rosuh.easywatermark.ui.ProductShellTransitions
import me.rosuh.easywatermark.ui.theme.currentMotionPolicy

/**
 * Shared About→Open Source overlay. Hosts must keep this composed (not `if (visible)`)
 * so exit can play. Transition is the Launch↔Editor short family, not About cover.
 */
@Composable
fun OpenSourceOverlayHost(
    visible: Boolean,
    onBack: () -> Unit,
    onOpenLink: (String) -> Unit,
    backIcon: Painter,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(),
) {
    val motionPolicy = currentMotionPolicy()
    AnimatedVisibility(
        visible = visible,
        enter = ProductShellTransitions.openSourceEnter(motionPolicy),
        exit = ProductShellTransitions.openSourceExit(motionPolicy),
        modifier = modifier.fillMaxSize(),
    ) {
        OpenSourceScreen(
            onBack = onBack,
            onOpenLink = onOpenLink,
            backIcon = backIcon,
            modifier = Modifier.fillMaxSize(),
            contentPadding = contentPadding,
        )
    }
}
