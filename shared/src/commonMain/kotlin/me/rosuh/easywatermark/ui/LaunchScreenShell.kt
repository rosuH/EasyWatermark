package me.rosuh.easywatermark.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.painter.Painter

/** @deprecated Use [LaunchScreen]. DEBUG witness compatibility only. */
@Deprecated(
    "Use LaunchScreen",
    ReplaceWith(
        "LaunchScreen(aboutIcon ?: rememberSharedAboutPainter(), onPickImageClick, onGoAbout ?: {}, modifier, startLogoAnimation, logo)",
    ),
)
@Composable
fun LaunchScreenShell(
    startLogoAnimation: Boolean,
    logo: @Composable (modifier: Modifier, startLogoAnimation: Boolean) -> Unit,
    onPickImageClick: () -> Unit,
    pickImageLabel: String = "", // ignored; Res inside LaunchScreen (S-i18n-2)
    aboutContentDescription: String? = null,
    aboutIcon: Painter? = null,
    onGoAbout: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    // pickImageLabel / aboutContentDescription ignored — S-i18n-2 resolves via Res inside LaunchScreen.
    LaunchScreen(
        aboutIcon = aboutIcon ?: rememberSharedAboutPainter(),
        onPickImage = onPickImageClick,
        onGoAbout = onGoAbout ?: {},
        modifier = modifier,
        startLogoAnimation = startLogoAnimation,
        logo = logo,
    )
}
