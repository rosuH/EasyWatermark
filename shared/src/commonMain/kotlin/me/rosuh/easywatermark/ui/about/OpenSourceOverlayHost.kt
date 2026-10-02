package me.rosuh.easywatermark.ui.about

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.updateTransition
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntOffset
import kotlin.math.roundToInt
import me.rosuh.easywatermark.ui.ProductShellTransitions
import me.rosuh.easywatermark.ui.ewmTestTagsAsResourceId
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
    val cover = updateTransition(visible, label = "openSourceOverlay")
    val layoutSlide = cover.animateFloat(
        transitionSpec = { ProductShellTransitions.shortFloatSpec(motionPolicy) },
        label = "openSourceLayoutSlide",
    ) { shown -> if (shown) 0f else 1f }
    var overlayWidthPx by remember { mutableIntStateOf(0) }
    AnimatedVisibility(
        visible = visible,
        enter = ProductShellTransitions.openSourceEnter(motionPolicy),
        exit = ProductShellTransitions.openSourceExit(motionPolicy),
        modifier = modifier
            .fillMaxSize()
            .ewmTestTagsAsResourceId()
            .onSizeChanged { overlayWidthPx = it.width }
            .offset {
                IntOffset((layoutSlide.value * overlayWidthPx).roundToInt(), 0)
            },
    ) {
        // Gap taps cannot pop About: OpenSourceScreen's Surface absorbs hits
        // so they never reach the page underneath, and About's back is disarmed
        // while this overlay is open. A clickable scrim would merge the title
        // into a fullscreen a11y node.
        OpenSourceScreen(
            onBack = onBack,
            onOpenLink = onOpenLink,
            backIcon = backIcon,
            modifier = Modifier.fillMaxSize(),
            contentPadding = contentPadding,
        )
    }
}
