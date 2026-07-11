package me.rosuh.easywatermark.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
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
import me.rosuh.easywatermark.data.model.TextPaintStyle
import me.rosuh.easywatermark.data.model.WatermarkTileMode
import me.rosuh.easywatermark.render.IosImageDecoder
import me.rosuh.easywatermark.ui.about.AboutDevCard
import me.rosuh.easywatermark.ui.about.AboutScreenIcons
import me.rosuh.easywatermark.ui.about.AboutScreenShell
import me.rosuh.easywatermark.ui.about.AboutScreenStrings
import me.rosuh.easywatermark.ui.compose.TileMode
import me.rosuh.easywatermark.ui.compose.TileModeLabels
import me.rosuh.easywatermark.ui.compose.TextPaintStyleLabels
import me.rosuh.easywatermark.ui.compose.TextPaintStyleOption
import me.rosuh.easywatermark.ui.save.SavePreviewStatus
import me.rosuh.easywatermark.ui.theme.AppTheme
import platform.UIKit.UIViewController

/**
 * iOS host boundary for shared Compose Multiplatform UI.
 *
 * SwiftUI remains the app entry/system-UI glue, but it can now embed this UIViewController to
 * render a real commonMain CMP shell from the `Shared.framework`.
 */
private data class IosWatermarkPreviewState(
    val png: ByteArray? = null,
    val status: String = "",
)

/** Production host for the rendered watermark preview; system picker/share/save stay in SwiftUI. */
class IosWatermarkPreviewHost {
    private var state by mutableStateOf(IosWatermarkPreviewState())

    fun viewController(): UIViewController = ComposeUIViewController {
        AppTheme {
            val current = state
            val preview = current.png?.let { bytes ->
                remember(bytes) { IosImageDecoder.decode(bytes) }
            }
            EditorPreviewFrame(
                hasImage = preview != null,
                emptyText = current.status,
                modifier = Modifier.fillMaxSize(),
            ) { previewModifier ->
                SavePreviewStatus(
                    status = current.status,
                    preview = preview,
                    previewContentDescription = "Watermarked preview",
                    modifier = previewModifier.padding(16.dp),
                )
            }
        }
    }

    fun update(png: ByteArray, status: String) {
        state = IosWatermarkPreviewState(png = png, status = status)
    }
}

/** Production host for the shared tile-mode control; Swift still owns workflow writes and re-rendering. */
class IosWatermarkTileModeHost(
    private val onValueChange: (WatermarkTileMode) -> Unit,
) {
    private var mode by mutableStateOf(WatermarkTileMode.REPEAT)

    fun viewController(): UIViewController = ComposeUIViewController {
        AppTheme {
            TileMode(
                labels = TileModeLabels(repeat = "Repeat", decal = "Single"),
                mode = mode,
                modifier = Modifier.fillMaxWidth(),
                onValueChange = { selectedMode ->
                    mode = selectedMode
                    onValueChange(selectedMode)
                },
            )
        }
    }

    fun update(mode: WatermarkTileMode) {
        this.mode = mode
    }
}

/** Production host for the shared text-style control; Swift still owns workflow writes and re-rendering. */
class IosTextPaintStyleHost(
    private val onValueChange: (TextPaintStyle) -> Unit,
) {
    private var style: TextPaintStyle by mutableStateOf(TextPaintStyle.Fill)

    fun viewController(): UIViewController = ComposeUIViewController {
        AppTheme {
            TextPaintStyleOption(
                labels = TextPaintStyleLabels(fill = "Fill", stroke = "Stroke"),
                style = style,
                modifier = Modifier.fillMaxWidth(),
                onValueChange = { selectedStyle ->
                    style = selectedStyle
                    onValueChange(selectedStyle)
                },
            )
        }
    }

    fun update(style: TextPaintStyle) {
        this.style = style
    }
}

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

    fun aboutScreenShellWitness(): UIViewController = ComposeUIViewController {
        AppTheme {
            val accent = ColorPainter(MaterialTheme.colorScheme.primary)
            val avatar = ColorPainter(MaterialTheme.colorScheme.primaryContainer)

            AboutScreenShell(
                versionName = "iOS shared witness",
                showBounds = false,
                dynamicColorOn = false,
                strings = AboutScreenStrings(
                    infoTitle = "Info",
                    versionTitle = "Version",
                    ratingTitle = "Rate",
                    feedbackTitle = "Feedback",
                    aboutTitle = "About",
                    updateLogTitle = "Update log",
                    openSourceTitle = "Open source",
                    privacyZhTitle = "Privacy zh",
                    privacyEnTitle = "Privacy en",
                    dynamicColorLabel = "Dynamic color",
                    showBoundsLabel = "Show bounds",
                ),
                icons = AboutScreenIcons(
                    back = accent,
                    version = accent,
                    rating = accent,
                    feedback = accent,
                    updateLog = accent,
                    openSource = accent,
                    privacyZh = accent,
                    privacyEn = accent,
                ),
                developerCard = AboutDevCard(
                    title = "Developer",
                    description = "Shared CMP about shell",
                    avatar = avatar,
                ),
                designerCard = AboutDevCard(
                    title = "Designer",
                    description = "Shared CMP about shell",
                    avatar = avatar,
                ),
                onBack = {},
                onVersion = {},
                onRate = {},
                onFeedback = {},
                onUpdateLog = {},
                onOpenSource = {},
                onPrivacyZh = {},
                onPrivacyEn = {},
                onDeveloper = {},
                onDesigner = {},
                onToggleBounds = {},
                onToggleDynamicColor = {},
                modifier = Modifier.fillMaxSize(),
                logo = { logoModifier ->
                    Box(logoModifier, contentAlignment = Alignment.Center) {
                        Text("EW", style = MaterialTheme.typography.labelMedium)
                    }
                },
            )
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
