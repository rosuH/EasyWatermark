package me.rosuh.easywatermark.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.Image
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.graphics.painter.ColorPainter
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.ComposeUIViewController
import me.rosuh.easywatermark.data.model.ImageInfo
import me.rosuh.easywatermark.data.model.MediaRef
import me.rosuh.easywatermark.data.model.TextPaintStyle
import me.rosuh.easywatermark.data.model.TextTypeface
import me.rosuh.easywatermark.data.model.WatermarkTileMode
import me.rosuh.easywatermark.render.IosImageDecoder
import me.rosuh.easywatermark.ui.about.AboutDevCard
import me.rosuh.easywatermark.ui.about.AboutScreenIcons
import me.rosuh.easywatermark.ui.about.AboutScreenShell
import me.rosuh.easywatermark.ui.about.AboutScreenStrings
import me.rosuh.easywatermark.ui.compose.TileMode
import me.rosuh.easywatermark.ui.compose.TileModeLabels
import me.rosuh.easywatermark.ui.compose.IconWatermarkOption
import me.rosuh.easywatermark.ui.compose.TextColorOption
import me.rosuh.easywatermark.ui.compose.TextColorOptionStrings
import me.rosuh.easywatermark.ui.compose.TextPaintStyleLabels
import me.rosuh.easywatermark.ui.compose.TextPaintStyleOption
import me.rosuh.easywatermark.ui.compose.TextTypeface as TextTypefaceOption
import me.rosuh.easywatermark.ui.compose.TextTypefaceLabels
import me.rosuh.easywatermark.ui.compose.SliderOption
import me.rosuh.easywatermark.ui.save.SavePreviewStatus
import me.rosuh.easywatermark.ui.save.SavedOutputActions
import me.rosuh.easywatermark.ui.save.SavedOutputActionsLabels
import me.rosuh.easywatermark.ui.theme.AppTheme
import platform.UIKit.UIViewController
import kotlin.math.abs

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

private data class IosWatermarkIconOptionState(
    val iconBytes: ByteArray? = null,
)

private data class IosSavedOutputActionsState(
    /** Share needs a staged temp file; may be false while Save still works from [resultPNG]. */
    val canShare: Boolean = false,
    val isSaving: Boolean = false,
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

/** Production host for the shared launch shell; Swift still presents the system source picker. */
class IosLaunchScreenHost(
    private val onPickImage: () -> Unit,
) {
    fun viewController(): UIViewController = ComposeUIViewController {
        AppTheme {
            LaunchScreenShell(
                pickImageLabel = "Pick a photo",
                startLogoAnimation = false,
                logo = { modifier, _ ->
                    Box(modifier.size(72.dp), contentAlignment = Alignment.Center) {
                        Text("EW", style = MaterialTheme.typography.titleLarge)
                    }
                },
                onPickImageClick = onPickImage,
            )
        }
    }
}

/** Production host for the shared icon-option shell; Swift still presents the system picker. */
class IosWatermarkIconOptionHost(
    private val onPick: () -> Unit,
) {
    private var state by mutableStateOf(IosWatermarkIconOptionState())

    fun viewController(): UIViewController = ComposeUIViewController {
        AppTheme {
            val current = state
            val icon = current.iconBytes?.let { bytes ->
                remember(bytes) { IosImageDecoder.decode(bytes) }
            }
            IconWatermarkOption(
                hasIcon = icon != null,
                pickLabel = "Pick icon",
                modifier = Modifier.fillMaxWidth(),
                onPick = onPick,
                preview = {
                    icon?.let { bitmap ->
                        Image(
                            bitmap = bitmap,
                            contentDescription = "Watermark icon",
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.size(32.dp),
                        )
                    }
                },
            )
        }
    }

    fun update(iconBytes: ByteArray?) {
        state = IosWatermarkIconOptionState(iconBytes = iconBytes)
    }
}

/** Production host for post-render output actions; Swift retains Share/Photos system UI. */
class IosSavedOutputActionsHost(
    private val onShare: () -> Unit,
    private val onSaveToPhotos: () -> Unit,
) {
    private var state by mutableStateOf(IosSavedOutputActionsState())

    fun viewController(): UIViewController = ComposeUIViewController {
        AppTheme {
            val current = state
            // Host is only composed when resultPNG exists. Secondary (Save) stays enabled unless
            // saving; primary (Share) independently requires a staged temp file (canShare).
            SavedOutputActions(
                labels = SavedOutputActionsLabels(
                    primary = "Share",
                    secondary = "Save to Photos",
                ),
                hasOutput = true,
                primaryEnabled = current.canShare && !current.isSaving,
                secondaryEnabled = !current.isSaving,
                onPrimaryAction = onShare,
                onSecondaryAction = onSaveToPhotos,
            )
        }
    }

    fun update(canShare: Boolean, isSaving: Boolean) {
        state = IosSavedOutputActionsState(
            canShare = canShare,
            isSaving = isSaving,
        )
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

/** Production host for the shared typeface control; Swift still owns workflow writes and re-rendering. */
class IosTextTypefaceHost(
    private val onValueChange: (TextTypeface) -> Unit,
) {
    private var typeface: TextTypeface by mutableStateOf(TextTypeface.Normal)

    fun viewController(): UIViewController = ComposeUIViewController {
        AppTheme {
            TextTypefaceOption(
                labels = TextTypefaceLabels(
                    normal = "Normal",
                    bold = "Bold",
                    italic = "Italic",
                    boldItalic = "BoldItalic",
                ),
                typeface = typeface,
                modifier = Modifier.fillMaxWidth(),
                onValueChange = { selectedTypeface ->
                    typeface = selectedTypeface
                    onValueChange(selectedTypeface)
                },
            )
        }
    }

    fun update(typeface: TextTypeface) {
        this.typeface = typeface
    }
}

/** Production host for the shared text-size slider; Swift still owns persistence and re-rendering. */
class IosTextSizeSliderHost(
    private val onValueChangeFinished: (Float) -> Unit,
) {
    private var textSize by mutableStateOf(14f)
    private var pendingTextSize: Float? = null

    fun viewController(): UIViewController = ComposeUIViewController {
        AppTheme {
            SliderOption(
                currentValue = textSize,
                valueRange = 1f..100f,
                modifier = Modifier.fillMaxWidth(),
                onValueChangeFinished = {
                    onValueChangeFinished(textSize)
                },
                onValueChange = { value ->
                    textSize = value
                    // Ignore an older async workflow update while the user is still dragging a newer value.
                    pendingTextSize = value
                },
            )
        }
    }

    fun update(textSize: Float) {
        if (pendingTextSize == null || pendingTextSize == textSize) {
            this.textSize = textSize
            pendingTextSize = null
        }
    }
}

/** Production host for the shared rotation slider; Swift still owns persistence and re-rendering. */
class IosWatermarkDegreeSliderHost(
    private val onValueChangeFinished: (Float) -> Unit,
) {
    private var degree by mutableStateOf(315f)
    private var pendingDegree: Float? = null

    fun viewController(): UIViewController = ComposeUIViewController {
        AppTheme {
            SliderOption(
                currentValue = degree,
                valueRange = 0f..360f,
                modifier = Modifier.fillMaxWidth(),
                onValueChangeFinished = {
                    onValueChangeFinished(degree)
                },
                onValueChange = { value ->
                    degree = value
                    // Ignore an older async workflow update while the user is still dragging a newer value.
                    pendingDegree = value
                },
            )
        }
    }

    fun update(degree: Float) {
        if (pendingDegree == null || pendingDegree == degree) {
            this.degree = degree
            pendingDegree = null
        }
    }
}

/** Production host for the shared opacity slider; Swift still owns persistence and re-rendering. */
class IosWatermarkAlphaSliderHost(
    private val onValueChangeFinished: (Float) -> Unit,
) {
    private var alphaPercent by mutableStateOf(100f)
    private var pendingAlphaPercent: Float? = null

    fun viewController(): UIViewController = ComposeUIViewController {
        AppTheme {
            SliderOption(
                currentValue = alphaPercent,
                valueRange = 0f..100f,
                modifier = Modifier.fillMaxWidth(),
                onValueChangeFinished = {
                    onValueChangeFinished(alphaPercent)
                },
                onValueChange = { value ->
                    alphaPercent = value
                    // Ignore an older async workflow update while the user is still dragging a newer value.
                    pendingAlphaPercent = value
                },
            )
        }
    }

    fun update(normalizedAlpha: Float) {
        val persistedPercent = normalizedAlpha.coerceIn(0f, 1f) * 100f
        val pending = pendingAlphaPercent
        if (pending == null || abs(pending - persistedPercent) < 0.001f) {
            alphaPercent = persistedPercent
            pendingAlphaPercent = null
        }
    }
}

/** Production host for the shared horizontal-gap slider; Swift still owns persistence and re-rendering. */
class IosWatermarkHorizontalGapSliderHost(
    private val onValueChangeFinished: (Float) -> Unit,
) {
    private var horizontalGap by mutableStateOf(0f)
    private var pendingHorizontalGap: Float? = null

    fun viewController(): UIViewController = ComposeUIViewController {
        AppTheme {
            SliderOption(
                currentValue = horizontalGap,
                valueRange = 0f..500f,
                modifier = Modifier.fillMaxWidth(),
                onValueChangeFinished = {
                    onValueChangeFinished(horizontalGap)
                },
                onValueChange = { value ->
                    horizontalGap = value
                    // Ignore an older async workflow update while the user is still dragging a newer value.
                    pendingHorizontalGap = value
                },
            )
        }
    }

    fun update(horizontalGap: Int) {
        val persistedGap = horizontalGap.coerceIn(0, 500).toFloat()
        if (pendingHorizontalGap == null || pendingHorizontalGap == persistedGap) {
            this.horizontalGap = persistedGap
            pendingHorizontalGap = null
        }
    }
}

/** Production host for the shared vertical-gap slider; Swift still owns persistence and re-rendering. */
class IosWatermarkVerticalGapSliderHost(
    private val onValueChangeFinished: (Float) -> Unit,
) {
    private var verticalGap by mutableStateOf(0f)
    private var pendingVerticalGap: Float? = null

    fun viewController(): UIViewController = ComposeUIViewController {
        AppTheme {
            SliderOption(
                currentValue = verticalGap,
                valueRange = 0f..500f,
                modifier = Modifier.fillMaxWidth(),
                onValueChangeFinished = {
                    onValueChangeFinished(verticalGap)
                },
                onValueChange = { value ->
                    verticalGap = value
                    // Ignore an older async workflow update while the user is still dragging a newer value.
                    pendingVerticalGap = value
                },
            )
        }
    }

    fun update(verticalGap: Int) {
        val persistedGap = verticalGap.coerceIn(0, 500).toFloat()
        if (pendingVerticalGap == null || pendingVerticalGap == persistedGap) {
            this.verticalGap = persistedGap
            pendingVerticalGap = null
        }
    }
}

/** Production host for the shared four-preset text-color palette; Swift still owns persistence and re-rendering. */
class IosWatermarkTextColorHost(
    private val onColorSelected: (Int) -> Unit,
) {
    private var color by mutableStateOf(0xFFFFB800.toInt())
    private var pendingColor: Int? = null

    fun viewController(): UIViewController = ComposeUIViewController {
        AppTheme {
            TextColorOption(
                currentColor = color,
                customText = "",
                strings = TextColorOptionStrings(
                    customLabel = "Custom color",
                    applyCustomButton = "Apply color",
                ),
                modifier = Modifier.fillMaxWidth(),
                palette = IOS_TEXT_COLOR_PALETTE,
                showCustomInput = false,
                onColorSelected = { selectedColor ->
                    color = selectedColor
                    pendingColor = selectedColor
                    onColorSelected(selectedColor)
                },
                onCustomTextChange = {},
                onApplyCustomText = {},
            )
        }
    }

    fun update(color: Int) {
        if (pendingColor == null || pendingColor == color) {
            this.color = color
            pendingColor = null
        }
    }

    private companion object {
        val IOS_TEXT_COLOR_PALETTE = listOf(
            0xFFFFB800.toInt(),
            0xFFFFFFFF.toInt(),
            0xFF000000.toInt(),
            0xFFFF0000.toInt(),
        )
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
