package me.rosuh.easywatermark.ui

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.platform.testTag
import me.rosuh.easywatermark.ui.theme.editorChromeColor

/**
 * Editor top bar (production layout). Icons supplied by the host (Android resources / platform bag).
 *
 * Design (Figma preview_edit): seamless editor chrome, white action icons; no elevated surface.
 * Container uses [editorChromeColor] so content editor theme (photo seed) matches body/title band.
 * Back icon size/tint matches About ([AboutScreen] IconButton + default 24.dp + onSurface).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditorTopBar(
    backIcon: Painter,
    addMoreImagesIcon: Painter,
    saveIcon: Painter,
    aboutIcon: Painter,
    backContentDescription: String,
    addMoreImagesContentDescription: String,
    saveContentDescription: String,
    aboutContentDescription: String,
    modifier: Modifier = Modifier,
    onBack: () -> Unit = {},
    onAddMoreImages: () -> Unit = {},
    onShowSaveDialog: () -> Unit = {},
    onGoAboutScreen: () -> Unit = {},
) {
    val container = editorChromeColor()
    // Design action icons are solid white on dark chrome
    val iconTint = Color.White
    TopAppBar(
        modifier = modifier,
        windowInsets = WindowInsets(0),
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = container,
            scrolledContainerColor = container,
            navigationIconContentColor = iconTint,
            actionIconContentColor = iconTint,
            titleContentColor = iconTint,
        ),
        title = {},
        navigationIcon = {
            // Same pattern as About: IconButton + default icon size (24.dp).
            IconButton(onClick = onBack) {
                Icon(
                    painter = backIcon,
                    contentDescription = backContentDescription,
                    tint = iconTint,
                )
            }
        },
        actions = {
            IconButton(onClick = onAddMoreImages) {
                Icon(
                    painter = addMoreImagesIcon,
                    contentDescription = addMoreImagesContentDescription,
                    tint = iconTint,
                )
            }
            IconButton(
                onClick = onShowSaveDialog,
                modifier = Modifier.testTag("sharedComposeSaveButton"),
            ) {
                Icon(
                    painter = saveIcon,
                    contentDescription = saveContentDescription,
                    tint = iconTint,
                )
            }
            IconButton(onClick = onGoAboutScreen) {
                Icon(
                    painter = aboutIcon,
                    contentDescription = aboutContentDescription,
                    tint = iconTint,
                )
            }
        },
    )
}
