package me.rosuh.easywatermark.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.ScaffoldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.painter.Painter

/**
 * Shared CMP gallery-dialog shell.
 *
 * Platform callers still provide system back handling, localized resources, picker behavior, and
 * image loading. This shell owns the in-dialog screen structure and selected-count UI state.
 */
@Composable
fun GalleryDialogShell(
    images: List<Image>,
    title: String,
    closeIcon: Painter,
    searchIcon: Painter,
    checkIcon: Painter,
    selectedCountIcon: Painter,
    closeContentDescription: String,
    searchContentDescription: String,
    selectedCountContentDescription: String,
    modifier: Modifier = Modifier,
    contentWindowInsets: WindowInsets = ScaffoldDefaults.contentWindowInsets,
    backHandler: @Composable (onBack: () -> Unit) -> Unit = {},
    onLoadImages: () -> Unit,
    onDismiss: (selected: Boolean) -> Unit = {},
    onImageSelected: (image: Image, index: Int, isSelected: Boolean) -> Unit,
    onPickImageViaSystem: () -> Unit = {},
    thumbnail: @Composable (image: Image, contentDescription: String, modifier: Modifier) -> Unit,
) {
    var selectedCount by remember {
        mutableIntStateOf(0)
    }
    AnimatedTransitionHost(
        onDismissRequest = {
            onDismiss(selectedCount > 0)
        },
        backHandler = backHandler,
    ) { dialogHelper ->
        LaunchedEffect(key1 = images.size) {
            onLoadImages()
        }
        Scaffold(
            modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background),
            contentWindowInsets = contentWindowInsets,
            topBar = {
                GalleryDialogTopBarShell(
                    title = title,
                    closeIcon = closeIcon,
                    searchIcon = searchIcon,
                    closeContentDescription = closeContentDescription,
                    searchContentDescription = searchContentDescription,
                    onClose = {
                        dialogHelper.triggerDismiss()
                    },
                    onSearch = {
                        onPickImageViaSystem.invoke()
                        dialogHelper.triggerDismiss()
                    },
                )
            },
        ) { innerPadding ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
            ) {
                GalleryImageGrid(
                    images = images,
                    checkIcon = checkIcon,
                    onImageSelected = { image, index, isChecked ->
                        selectedCount += if (isChecked) +1 else -1
                        onImageSelected(image, index, isChecked)
                    },
                    thumbnail = thumbnail,
                )

                GallerySelectedCountFab(
                    selectedCount = selectedCount,
                    icon = selectedCountIcon,
                    contentDescription = selectedCountContentDescription,
                    modifier = Modifier.align(Alignment.BottomCenter),
                    onClick = {
                        dialogHelper.triggerDismiss()
                    },
                )
            }
        }
    }
}
