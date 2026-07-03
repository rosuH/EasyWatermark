package me.rosuh.easywatermark.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.material3.Text
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.painter.ColorPainter
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.ComposeUIViewController
import me.rosuh.easywatermark.data.model.ImageInfo
import me.rosuh.easywatermark.data.model.MediaRef
import me.rosuh.easywatermark.ui.theme.AppTheme
import platform.UIKit.UIViewController

/**
 * iOS host boundary for shared Compose Multiplatform UI.
 *
 * SwiftUI remains the app entry/system-UI glue, but it can now embed this UIViewController to
 * render a real commonMain CMP shell from the `Shared.framework`.
 */
object IosSharedComposeHost {
    fun launchScreenShellWitness(): UIViewController = ComposeUIViewController {
        AppTheme {
            LaunchScreenShell(
                pickImageLabel = "Choose Images",
                aboutContentDescription = "About EasyWatermark",
                aboutIcon = ColorPainter(MaterialTheme.colorScheme.primary),
                startLogoAnimation = false,
                logo = { modifier, _ ->
                    Box(modifier.size(44.dp), contentAlignment = Alignment.Center) {
                        Text("EW", style = MaterialTheme.typography.labelMedium)
                    }
                },
                onPickImageClick = {},
                onGoAbout = {},
            )
        }
    }

    fun galleryDialogShellWitness(): UIViewController = ComposeUIViewController {
        AppTheme {
            var images by remember {
                mutableStateOf(
                    List(8) { index ->
                        Image(
                            id = index,
                            uri = MediaRef("ios-gallery-witness-$index"),
                            name = "Gallery witness ${index + 1}",
                            size = 0L,
                            date = 0L,
                        )
                    }
                )
            }

            GalleryDialogShell(
                images = images,
                title = "Photos",
                closeIcon = ColorPainter(MaterialTheme.colorScheme.onSurface),
                searchIcon = ColorPainter(MaterialTheme.colorScheme.primary),
                checkIcon = ColorPainter(MaterialTheme.colorScheme.onSecondary),
                selectedCountIcon = ColorPainter(MaterialTheme.colorScheme.onPrimary),
                closeContentDescription = "Close gallery witness",
                searchContentDescription = "Open system picker",
                selectedCountContentDescription = "Selected image count",
                modifier = Modifier.fillMaxSize(),
                contentWindowInsets = WindowInsets(),
                onLoadImages = {},
                onDismiss = {},
                onImageSelected = { _, index, isSelected ->
                    images = images.mapIndexed { itemIndex, image ->
                        if (itemIndex == index) image.copy(check = isSelected) else image
                    }
                },
                onPickImageViaSystem = {},
            ) { image, _, thumbnailModifier ->
                val color = when (image.id % 4) {
                    0 -> MaterialTheme.colorScheme.primaryContainer
                    1 -> MaterialTheme.colorScheme.secondaryContainer
                    2 -> MaterialTheme.colorScheme.tertiaryContainer
                    else -> MaterialTheme.colorScheme.surfaceVariant
                }
                Box(thumbnailModifier.background(color))
            }
        }
    }

    fun editorScreenShellWitness(): UIViewController = ComposeUIViewController {
        AppTheme {
            val images = remember {
                listOf(
                    ImageInfo(MediaRef("ios-cmp-witness-1")),
                    ImageInfo(MediaRef("ios-cmp-witness-2")),
                    ImageInfo(MediaRef("ios-cmp-witness-3")),
                )
            }
            var selectedImage by remember { mutableStateOf(images.first()) }

            EditorScreenShell(
                showPhotoStrip = true,
                modifier = Modifier.fillMaxSize(),
                topBar = { modifier ->
                    Box(modifier, contentAlignment = Alignment.Center) {
                        Text("Shared CMP editor shell", style = MaterialTheme.typography.labelMedium)
                    }
                },
                preview = { modifier ->
                    EditorPreviewFrame(
                        hasImage = true,
                        emptyText = "No preview",
                        modifier = modifier,
                    ) { previewModifier ->
                        Box(
                            modifier = previewModifier
                                .background(MaterialTheme.colorScheme.primaryContainer),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text("Preview slot", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                },
                photoStrip = { modifier ->
                    EditorPhotoStrip(
                        images = images,
                        selectedImage = selectedImage,
                        modifier = modifier,
                        onImageSelected = { selectedImage = it },
                    ) { imageInfo, _, thumbnailModifier ->
                        val color = if (imageInfo == selectedImage) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.surfaceVariant
                        }
                        Box(thumbnailModifier.background(color))
                    }
                },
                bottomControls = {
                    EditorBottomControlsShell(
                        tabs = listOf(
                            EditorBottomControlTab("Content", listOf("Text", "Icon")),
                            EditorBottomControlTab("Style", listOf("Size", "Opacity"), useCompactPadding = true),
                            EditorBottomControlTab("Layout", listOf("Gap", "Degree")),
                        ),
                        optionControl = { option, modifier ->
                            EditorOptionControlFrame(modifier) { innerModifier ->
                                Box(innerModifier, contentAlignment = Alignment.Center) {
                                    Text("$option option", style = MaterialTheme.typography.labelMedium)
                                }
                            }
                        },
                        optionItem = { option ->
                            Text(option, style = MaterialTheme.typography.labelSmall)
                        },
                    )
                },
            )
        }
    }
}
