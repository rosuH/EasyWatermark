package me.rosuh.easywatermark.ui

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.painter.Painter

/**
 * Shared CMP editor top-bar shell.
 *
 * Platform callers provide icons and actions; picker/save/about behavior remains at the platform edge.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditorTopBarShell(
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
    TopAppBar(
        modifier = modifier,
        windowInsets = WindowInsets(0),
        title = {},
        navigationIcon = {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onBack) {
                    Icon(
                        painter = backIcon,
                        contentDescription = backContentDescription,
                    )
                }
            }
        },
        actions = {
            IconButton(onClick = onAddMoreImages) {
                Icon(
                    painter = addMoreImagesIcon,
                    contentDescription = addMoreImagesContentDescription,
                )
            }
            IconButton(onClick = onShowSaveDialog) {
                Icon(
                    painter = saveIcon,
                    contentDescription = saveContentDescription,
                )
            }
            IconButton(onClick = onGoAboutScreen) {
                Icon(
                    painter = aboutIcon,
                    contentDescription = aboutContentDescription,
                )
            }
        },
    )
}
