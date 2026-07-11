package me.rosuh.easywatermark.ui

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.painter.Painter

/**
 * Shared CMP launch-screen shell.
 *
 * Android keeps the permission request and animated legacy logo edge; callers inject localized
 * strings, icon painter, and the platform logo slot.
 */
@Composable
fun LaunchScreenShell(
    pickImageLabel: String,
    startLogoAnimation: Boolean,
    logo: @Composable (modifier: Modifier, startLogoAnimation: Boolean) -> Unit,
    onPickImageClick: () -> Unit,
    aboutContentDescription: String? = null,
    aboutIcon: Painter? = null,
    onGoAbout: (() -> Unit)? = null,
) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        logo(
            Modifier
                .padding(top = maxHeight * 0.2f)
                .align(Alignment.TopCenter),
            startLogoAnimation,
        )

        Button(
            modifier = Modifier
                .align(Alignment.Center)
                .padding(top = maxHeight * 0.3f),
            // Parity (ADR-0011): production buttons are sharp-cornered.
            shape = RectangleShape,
            onClick = onPickImageClick,
        ) {
            Text(pickImageLabel)
        }

        if (aboutContentDescription != null && aboutIcon != null && onGoAbout != null) {
            IconButton(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = maxHeight * 0.03f),
                onClick = onGoAbout,
            ) {
                Icon(
                    painter = aboutIcon,
                    contentDescription = aboutContentDescription,
                )
            }
        }
    }
}
