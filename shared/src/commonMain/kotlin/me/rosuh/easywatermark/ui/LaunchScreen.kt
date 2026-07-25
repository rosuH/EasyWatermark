package me.rosuh.easywatermark.ui

import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import me.rosuh.easywatermark.shared.generated.resources.Res
import me.rosuh.easywatermark.shared.generated.resources.about_title_about
import me.rosuh.easywatermark.shared.generated.resources.privacy_confidence_pick
import me.rosuh.easywatermark.shared.generated.resources.tips_pick_image
import org.jetbrains.compose.resources.stringResource

/**
 * Shared product launch screen (historical name; same role as Android View-era Launch).
 *
 * Hosts supply [logo] (Android may inject animated logo) and [aboutIcon].
 * S-i18n-2: labels from composeResources [Res], not edge bags.
 * I4: privacy confidence line near pick CTA (on-device + selected-only).
 */
@Composable
fun LaunchScreen(
    aboutIcon: Painter,
    onPickImage: () -> Unit,
    onGoAbout: () -> Unit,
    modifier: Modifier = Modifier,
    startLogoAnimation: Boolean = true,
    logo: @Composable (modifier: Modifier, startLogoAnimation: Boolean) -> Unit = { logoMod, animate ->
        BrandLogo(modifier = logoMod, animate = animate)
    },
) {
    val pickImageLabel = stringResource(Res.string.tips_pick_image)
    val privacyPick = stringResource(Res.string.privacy_confidence_pick)
    val aboutContentDescription = stringResource(Res.string.about_title_about)
    val aboutInteraction = remember { MutableInteractionSource() }
    val aboutPressed by aboutInteraction.collectIsPressedAsState()

    // Full-bleed background (edge-to-edge); content respects safe drawing insets.
    Surface(
        modifier = modifier
            .fillMaxSize()
            .testTag("sharedComposeLaunchScreen"),
        color = MaterialTheme.colorScheme.background,
    ) {
        BoxWithConstraints(Modifier.fillMaxSize().safeDrawingPadding()) {
            logo(
                Modifier
                    .padding(top = maxHeight * 0.2f)
                    .align(Alignment.TopCenter),
                startLogoAnimation,
            )

            Column(
                modifier = Modifier
                    .align(Alignment.Center)
                    .padding(top = maxHeight * 0.3f)
                    .padding(horizontal = 24.dp)
                    .fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Button(
                    modifier = Modifier.testTag("launchPickImageButton"),
                    shape = RectangleShape,
                    onClick = onPickImage,
                ) {
                    Text(pickImageLabel)
                }
                // I4: succinct on-device + selected-only confidence at pick decision point.
                Text(
                    text = privacyPick,
                    modifier = Modifier
                        .widthIn(max = 320.dp)
                        .testTag("launchPrivacyConfidence"),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            }

            IconButton(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = maxHeight * 0.03f)
                    .testTag("launchAboutButton"),
                onClick = onGoAbout,
                interactionSource = aboutInteraction,
            ) {
                // Visible pressed state (alpha) for Material IconButton on all platforms.
                Icon(
                    painter = aboutIcon,
                    contentDescription = aboutContentDescription,
                    tint = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.alpha(if (aboutPressed) 0.45f else 1f),
                )
            }
        }
    }
}
