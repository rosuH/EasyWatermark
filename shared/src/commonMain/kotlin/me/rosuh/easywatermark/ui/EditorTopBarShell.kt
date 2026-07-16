package me.rosuh.easywatermark.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.painter.Painter

/** @deprecated Use [EditorTopBar]. Kept only until Desktop/witness call sites migrate. */
@Deprecated("Use EditorTopBar", ReplaceWith("EditorTopBar(backIcon, addMoreImagesIcon, saveIcon, aboutIcon, backContentDescription, addMoreImagesContentDescription, saveContentDescription, aboutContentDescription, modifier, onBack, onAddMoreImages, onShowSaveDialog, onGoAboutScreen)"))
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
    EditorTopBar(
        backIcon = backIcon,
        addMoreImagesIcon = addMoreImagesIcon,
        saveIcon = saveIcon,
        aboutIcon = aboutIcon,
        backContentDescription = backContentDescription,
        addMoreImagesContentDescription = addMoreImagesContentDescription,
        saveContentDescription = saveContentDescription,
        aboutContentDescription = aboutContentDescription,
        modifier = modifier,
        onBack = onBack,
        onAddMoreImages = onAddMoreImages,
        onShowSaveDialog = onShowSaveDialog,
        onGoAboutScreen = onGoAboutScreen,
    )
}
