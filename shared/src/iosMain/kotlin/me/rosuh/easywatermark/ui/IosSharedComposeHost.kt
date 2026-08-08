package me.rosuh.easywatermark.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.graphics.painter.ColorPainter
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.ComposeUIViewController
import me.rosuh.easywatermark.data.model.ImageInfo
import me.rosuh.easywatermark.data.model.ImageInfoUi
import me.rosuh.easywatermark.data.model.MediaRef
import me.rosuh.easywatermark.ui.compose.parseArgbHexColor
import me.rosuh.easywatermark.data.model.WatermarkConfigRules
import me.rosuh.easywatermark.data.model.WaterMark
import me.rosuh.easywatermark.data.model.FuncType
import me.rosuh.easywatermark.data.model.TextPaintStyle
import me.rosuh.easywatermark.data.model.TextTypeface
import me.rosuh.easywatermark.data.model.WatermarkConfigChange
import me.rosuh.easywatermark.data.model.WatermarkTileMode
import me.rosuh.easywatermark.render.IosImageDecoder
import me.rosuh.easywatermark.ui.about.AboutDevCard
import me.rosuh.easywatermark.ui.about.AboutScreenIcons
import me.rosuh.easywatermark.ui.about.AboutScreenShell
import me.rosuh.easywatermark.ui.compose.TileMode
import me.rosuh.easywatermark.ui.compose.IconWatermarkOption
import me.rosuh.easywatermark.ui.compose.TextColorOption
import me.rosuh.easywatermark.ui.compose.formatArgbHexColor
import me.rosuh.easywatermark.ui.compose.TextContentOption
import me.rosuh.easywatermark.ui.compose.TextPaintStyleLabels
import me.rosuh.easywatermark.ui.compose.TextPaintStyleOption
import me.rosuh.easywatermark.ui.compose.TextTypeface as TextTypefaceOption
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
internal class IosWatermarkPreviewHost {
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
internal class IosLaunchScreenHost(
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
internal class IosWatermarkIconOptionHost(
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
internal class IosSavedOutputActionsHost(
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
internal class IosWatermarkTileModeHost(
    private val onValueChange: (WatermarkTileMode) -> Unit,
) {
    private var mode by mutableStateOf(WatermarkTileMode.REPEAT)

    fun viewController(): UIViewController = ComposeUIViewController {
        AppTheme {
            TileMode(
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
internal class IosTextPaintStyleHost(
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
internal class IosTextTypefaceHost(
    private val onValueChange: (TextTypeface) -> Unit,
) {
    private var typeface: TextTypeface by mutableStateOf(TextTypeface.Normal)

    fun viewController(): UIViewController = ComposeUIViewController {
        AppTheme {
            TextTypefaceOption(
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
internal class IosTextSizeSliderHost(
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
internal class IosWatermarkDegreeSliderHost(
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
internal class IosWatermarkAlphaSliderHost(
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
internal class IosWatermarkHorizontalGapSliderHost(
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
internal class IosWatermarkVerticalGapSliderHost(
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

/**
 * Production host for shared watermark text editing ([TextContentOption]).
 *
 * Replaces the SwiftUI TextField + Apply path. Swift still owns * [WatermarkWorkflow] persistence and re-render; no template icon (Templates stay SwiftUI).
 */
internal class IosTextContentOptionHost(
    private val onTextChange: (String) -> Unit,
) {
    private var text by mutableStateOf("")
    private var pendingText: String? = null

    fun viewController(): UIViewController = ComposeUIViewController {
        AppTheme {
            TextContentOption(
                text = text,
                                // Templates remain the proven SwiftUI section; do not open TemplateListSheet here.
                templateIcon = null,
                // XCUITest taps the host row → sheet (openSignal left 0; row click opens sheet).
                openSignal = 0,
                modifier = Modifier.fillMaxWidth(),
                onTextChange = { next ->
                    text = next
                    pendingText = next
                    onTextChange(next)
                },
            )
        }
    }

    fun update(text: String) {
        if (pendingText == null || pendingText == text) {
            this.text = text
            pendingText = null
        }
    }
}

/** Production host for the shared four-preset text-color palette; Swift still owns persistence and re-rendering. */
internal class IosWatermarkTextColorHost(
    private val onColorSelected: (Int) -> Unit,
) {
    private var color by mutableStateOf(0xFFFFB800.toInt())
    private var pendingColor: Int? = null

    fun viewController(): UIViewController = ComposeUIViewController {
        AppTheme {
            TextColorOption(
                currentColor = color,
                customText = "",
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

/**
 * Production iOS editor route ( / A5a): one [ComposeUIViewController] owning shared
 * [EditorScreenShell] with a flat scrollable options column.
 *
 * Swift retains PhotosPicker, Templates, Share/Save system UI, and [WatermarkWorkflow] writes.
 */
internal class IosEditorScreenHost(
    private val onPickIcon: () -> Unit,
    private val onTextChange: (String) -> Unit,
    private val onDegreeFinished: (Float) -> Unit,
    private val onTileModeChange: (WatermarkTileMode) -> Unit,
    private val onAlphaFinished: (Float) -> Unit,
    private val onColorSelected: (Int) -> Unit,
    private val onTextSizeFinished: (Float) -> Unit,
    private val onHorizontalGapFinished: (Float) -> Unit,
    private val onVerticalGapFinished: (Float) -> Unit,
    private val onTypefaceChange: (TextTypeface) -> Unit,
    private val onTextStyleChange: (TextPaintStyle) -> Unit,
    private val onShare: () -> Unit,
    private val onSaveToPhotos: () -> Unit,
) {
    private var state by mutableStateOf(IosEditorScreenState())

    private var degree by mutableStateOf(315f)
    private var pendingDegree: Float? = null
    private var alphaPercent by mutableStateOf(100f)
    private var pendingAlphaPercent: Float? = null
    private var textSize by mutableStateOf(14f)
    private var pendingTextSize: Float? = null
    private var horizontalGap by mutableStateOf(0f)
    private var pendingHorizontalGap: Float? = null
    private var verticalGap by mutableStateOf(0f)
    private var pendingVerticalGap: Float? = null
    private var pendingText: String? = null
    private var pendingColor: Int? = null
    private var pendingTileMode: WatermarkTileMode? = null
    private var pendingTypeface: TextTypeface? = null
    private var pendingTextStyle: TextPaintStyle? = null

    fun viewController(): UIViewController = ComposeUIViewController {
        AppTheme {
            val current = state
            val previewBitmap = current.previewPng?.let { bytes ->
                remember(bytes) { IosImageDecoder.decode(bytes) }
            }
            val iconBitmap = current.iconBytes?.let { bytes ->
                remember(bytes) { IosImageDecoder.decode(bytes) }
            }
            val tileLabel =
                if (current.tileMode == WatermarkTileMode.CLAMP) "Tile mode Single" else "Tile mode Repeat"
            val styleLabel =
                if (current.textStyle.serializeKey() == 1) "Text style Stroke" else "Text style Fill"
            val typefaceLabel = when (current.typeface.serializeKey()) {
                2 -> "Typeface Bold"
                1 -> "Typeface Italic"
                3 -> "Typeface BoldItalic"
                else -> "Typeface Normal"
            }
            val modeLabel = if (current.isImageMode) "Image" else "Text"
            val textHostLabel = "Watermark text ${current.text}"

            EditorScreenShell(
                showPhotoStrip = false,
                modifier = Modifier
                    .fillMaxSize()
                    .testTag("sharedComposeEditorScreen"),
                topBar = { topBarModifier ->
                    Column(
                        modifier = topBarModifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Text(
                            text = "Mode: $modeLabel",
                            style = MaterialTheme.typography.labelMedium,
                            modifier = Modifier
                                .testTag("watermarkModeLabel")
                                .semantics { contentDescription = "Mode: $modeLabel" },
                        )
                        Text(
                            text = current.statusLine,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                preview = { previewModifier ->
                    EditorPreviewFrame(
                        hasImage = previewBitmap != null,
                        emptyText = current.statusLine.ifBlank { "No preview" },
                        modifier = previewModifier
                            .fillMaxSize()
                            .testTag("sharedComposeWatermarkPreview")
                            .semantics { contentDescription = "Watermarked preview" },
                    ) { frameModifier ->
                        SavePreviewStatus(
                            status = current.statusLine,
                            preview = previewBitmap,
                            previewContentDescription = "Watermarked preview",
                            modifier = frameModifier.padding(8.dp),
                        )
                    }
                },
                photoStrip = {},
                bottomControls = {
                    // A5a: sticky saved-output (export stays visible) + bounded verticalScroll options.
                    // Cap options ~300dp so weight(preview) keeps a usable image area on small phones
                    // (560dp previously crushed the preview when SwiftUI Templates strip is present).
                    Column(modifier = Modifier.fillMaxWidth()) {
                        if (current.hasOutput) {
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
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 12.dp, vertical = 4.dp)
                                    .testTag("sharedComposeSavedOutputActions"),
                            )
                        }
                        // U3: Android-parity shared bottom controls (session callbacks stay Swift workflow edge).
                        val waterMark = WaterMark(
                            text = current.text,
                            textSize = textSize,
                            textColor = current.textColor,
                            textStyle = current.textStyle,
                            textTypeface = current.typeface,
                            alpha = WatermarkConfigRules.alphaPercentToByte(alphaPercent),
                            degree = degree,
                            hGap = horizontalGap.toInt(),
                            vGap = verticalGap.toInt(),
                            iconUri = if (iconBitmap != null) MediaRef("ios-icon") else MediaRef.Empty,
                            markMode = if (current.isImageMode) {
                                me.rosuh.easywatermark.data.model.WatermarkMode.Image
                            } else {
                                me.rosuh.easywatermark.data.model.WatermarkMode.Text
                            },
                            enableBounds = false,
                            tileMode = current.tileMode,
                        )
                        var colorDraft by remember(current.textColor) {
                            mutableStateOf(formatArgbHexColor(current.textColor))
                        }
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(max = 320.dp)
                                .verticalScroll(rememberScrollState())
                                .testTag("sharedComposeEditorOptions")
                                .padding(horizontal = 8.dp, vertical = 4.dp),
                        ) {
                            EditorBottomControls(
                                waterMark = waterMark,
                                                                templateIcon = null,
                                // F2: typed WatermarkConfigChange from EditorBottomControls.
                                onValueChange = { change ->
                                    when (change) {
                                        is WatermarkConfigChange.Text -> {
                                            pendingText = change.text
                                            state = state.copy(text = change.text)
                                            onTextChange(change.text)
                                        }
                                        is WatermarkConfigChange.Degree -> {
                                            degree = change.degree
                                            pendingDegree = change.degree
                                            onDegreeFinished(change.degree)
                                        }
                                        is WatermarkConfigChange.AlphaPercent -> {
                                            alphaPercent = change.percent
                                            pendingAlphaPercent = change.percent
                                            onAlphaFinished(change.percent)
                                        }
                                        is WatermarkConfigChange.TextSize -> {
                                            textSize = change.size
                                            pendingTextSize = change.size
                                            onTextSizeFinished(change.size)
                                        }
                                        is WatermarkConfigChange.HorizontalGap -> {
                                            val next = change.gap.toFloat()
                                            horizontalGap = next
                                            pendingHorizontalGap = next
                                            onHorizontalGapFinished(next)
                                        }
                                        is WatermarkConfigChange.VerticalGap -> {
                                            val next = change.gap.toFloat()
                                            verticalGap = next
                                            pendingVerticalGap = next
                                            onVerticalGapFinished(next)
                                        }
                                        is WatermarkConfigChange.Color -> {
                                            pendingColor = change.color
                                            state = state.copy(textColor = change.color)
                                            onColorSelected(change.color)
                                        }
                                        is WatermarkConfigChange.TileMode -> {
                                            pendingTileMode = change.tileMode
                                            state = state.copy(tileMode = change.tileMode)
                                            onTileModeChange(change.tileMode)
                                        }
                                        is WatermarkConfigChange.Typeface -> {
                                            pendingTypeface = change.typeface
                                            state = state.copy(typeface = change.typeface)
                                            onTypefaceChange(change.typeface)
                                        }
                                        is WatermarkConfigChange.Icon -> {
                                            // Icon bytes arrive via PhotosPicker + update(); picker is the edge.
                                            onPickIcon()
                                        }
                                    }
                                },
                                onGoTemplateList = { /* Swift Templates strip remains until full ProductApp */ },
                                colorOption = { optionModifier, mark, onColor ->
                                    Box(
                                        modifier = optionModifier
                                            .testTag("sharedComposeTextColor")
                                            .semantics {
                                                contentDescription =
                                                    "Text color ${formatArgbHexColor(mark.textColor)}"
                                            },
                                    ) {
                                        TextColorOption(
                                            currentColor = mark.textColor,
                                            customText = colorDraft,
                                            modifier = Modifier.fillMaxWidth(),
                                            showCustomInput = false,
                                            onColorSelected = onColor,
                                            onCustomTextChange = { colorDraft = it },
                                            onApplyCustomText = {
                                                parseArgbHexColor(colorDraft)?.let(onColor)
                                            },
                                        )
                                    }
                                },
                                iconOption = { optionModifier, mark, _ ->
                                    Box(
                                        modifier = optionModifier
                                            .testTag("sharedComposeIconWatermarkOption")
                                            .semantics {
                                                contentDescription = if (iconBitmap == null) {
                                                    "Watermark icon not selected"
                                                } else {
                                                    "Watermark icon selected"
                                                }
                                            },
                                    ) {
                                        IconWatermarkOption(
                                            hasIcon = iconBitmap != null,
                                            pickLabel = "Pick icon",
                                            modifier = Modifier.fillMaxWidth(),
                                            onPick = onPickIcon,
                                            preview = {
                                                iconBitmap?.let { bitmap ->
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
                                },
                                optionItem = { spec, selected ->
                                    val label = when (spec.type) {
                                        FuncType.Text -> "Text"
                                        FuncType.Icon -> "Image"
                                        FuncType.TileMode -> "Tile"
                                        FuncType.TextSize -> "Size"
                                        FuncType.TextTypeFace -> "Typeface"
                                        FuncType.Color -> "Color"
                                        FuncType.Alpha -> "Opacity"
                                        FuncType.Degree -> "Rotate"
                                        FuncType.Horizon -> "H gap"
                                        FuncType.Vertical -> "V gap"
                                    }
                                    EditorOptionItem(
                                        icon = ColorPainter(MaterialTheme.colorScheme.primary),
                                        contentDescription = label,
                                        label = label,
                                        selected = selected,
                                    )
                                },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("sharedComposeEditorBottomControls"),
                            )
                            // Paint style is Android-extra on Desktop; keep for iOS parity with prior host.
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("sharedComposeTextPaintStyle")
                                    .semantics { contentDescription = styleLabel },
                            ) {
                                TextPaintStyleOption(
                                    labels = TextPaintStyleLabels(fill = "Fill", stroke = "Stroke"),
                                    style = current.textStyle,
                                    modifier = Modifier.fillMaxWidth(),
                                    onValueChange = { selected ->
                                        pendingTextStyle = selected
                                        state = state.copy(textStyle = selected)
                                        onTextStyleChange(selected)
                                    },
                                )
                            }
                            // XCUITest-friendly state summaries (retain prior a11y tags).
                            Text(
                                text = textHostLabel,
                                style = MaterialTheme.typography.labelSmall,
                                modifier = Modifier
                                    .testTag("sharedComposeTextContent")
                                    .semantics { contentDescription = textHostLabel },
                            )
                            Text(
                                text = tileLabel,
                                style = MaterialTheme.typography.labelSmall,
                                modifier = Modifier
                                    .testTag("sharedComposeTileMode")
                                    .semantics { contentDescription = tileLabel },
                            )
                            Text(
                                text = typefaceLabel,
                                style = MaterialTheme.typography.labelSmall,
                                modifier = Modifier
                                    .testTag("sharedComposeTextTypeface")
                                    .semantics { contentDescription = typefaceLabel },
                            )
                            Text(
                                text = "Gaps: H ${horizontalGap.toInt()}  V ${verticalGap.toInt()}",
                                style = MaterialTheme.typography.labelSmall,
                                modifier = Modifier
                                    .testTag("watermarkGapLabel")
                                    .semantics {
                                        contentDescription =
                                            "Gaps: H ${horizontalGap.toInt()}  V ${verticalGap.toInt()}"
                                    },
                            )
                        }
                    }
                },
            )
        }
    }

    fun update(
        text: String,
        degree: Float,
        tileMode: WatermarkTileMode,
        normalizedAlpha: Float,
        textColor: Int,
        textSize: Float,
        horizontalGap: Int,
        verticalGap: Int,
        typeface: TextTypeface,
        textStyle: TextPaintStyle,
        isImageMode: Boolean,
        iconBytes: ByteArray?,
        previewPng: ByteArray?,
        statusLine: String,
        hasOutput: Boolean,
        canShare: Boolean,
        isSaving: Boolean,
    ) {
        if (pendingText == null || pendingText == text) {
            pendingText = null
            state = state.copy(text = text)
        }
        if (pendingDegree == null || pendingDegree == degree) {
            this.degree = degree
            pendingDegree = null
        }
        val persistedAlpha = normalizedAlpha.coerceIn(0f, 1f) * 100f
        if (pendingAlphaPercent == null ||
            abs((pendingAlphaPercent ?: 0f) - persistedAlpha) < 0.001f
        ) {
            alphaPercent = persistedAlpha
            pendingAlphaPercent = null
        }
        if (pendingTextSize == null || pendingTextSize == textSize) {
            this.textSize = textSize
            pendingTextSize = null
        }
        val hGap = horizontalGap.coerceIn(0, 500).toFloat()
        if (pendingHorizontalGap == null || pendingHorizontalGap == hGap) {
            this.horizontalGap = hGap
            pendingHorizontalGap = null
        }
        val vGap = verticalGap.coerceIn(0, 500).toFloat()
        if (pendingVerticalGap == null || pendingVerticalGap == vGap) {
            this.verticalGap = vGap
            pendingVerticalGap = null
        }
        if (pendingColor == null || pendingColor == textColor) {
            pendingColor = null
            state = state.copy(textColor = textColor)
        }
        val nextTile = if (pendingTileMode == null || pendingTileMode == tileMode) {
            pendingTileMode = null
            tileMode
        } else {
            state.tileMode
        }
        val nextTypeface = if (pendingTypeface == null || pendingTypeface == typeface) {
            pendingTypeface = null
            typeface
        } else {
            state.typeface
        }
        val nextTextStyle = if (pendingTextStyle == null || pendingTextStyle == textStyle) {
            pendingTextStyle = null
            textStyle
        } else {
            state.textStyle
        }
        state = state.copy(
            tileMode = nextTile,
            typeface = nextTypeface,
            textStyle = nextTextStyle,
            isImageMode = isImageMode,
            iconBytes = iconBytes,
            previewPng = previewPng,
            statusLine = statusLine,
            hasOutput = hasOutput,
            canShare = canShare,
            isSaving = isSaving,
        )
    }

}

private data class IosEditorScreenState(
    val text: String = "EasyWatermark 水印",
    val tileMode: WatermarkTileMode = WatermarkTileMode.REPEAT,
    val textColor: Int = 0xFFFFB800.toInt(),
    val typeface: TextTypeface = TextTypeface.Normal,
    val textStyle: TextPaintStyle = TextPaintStyle.Fill,
    val isImageMode: Boolean = false,
    val iconBytes: ByteArray? = null,
    val previewPng: ByteArray? = null,
    val statusLine: String = "",
    val hasOutput: Boolean = false,
    val canShare: Boolean = false,
    val isSaving: Boolean = false,
)

@Composable
private fun labeledEditorSlider(
    tag: String,
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    onValueChange: (Float) -> Unit,
    onFinished: () -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .testTag(tag)
            .semantics { contentDescription = label },
    ) {
        SliderOption(
            currentValue = value,
            valueRange = range,
            modifier = Modifier.fillMaxWidth(),
            onValueChangeFinished = onFinished,
            onValueChange = onValueChange,
        )
    }
}

object IosSharedComposeHost {
    fun launchScreenShellWitness(): UIViewController = ComposeUIViewController {
        AppTheme {
            LaunchScreenShell(
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
            // Filmstrip uses ImageInfoUi (P0 projection); Session still owns mutable ImageInfo.
            val images = remember {
                listOf(
                    ImageInfoUi(MediaRef("ios-cmp-witness-1")),
                    ImageInfoUi(MediaRef("ios-cmp-witness-2")),
                    ImageInfoUi(MediaRef("ios-cmp-witness-3")),
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
                        optionControl = { option, modifier, _ ->
                            EditorOptionControlFrame(modifier) { innerModifier ->
                                Box(innerModifier, contentAlignment = Alignment.Center) {
                                    Text("$option option", style = MaterialTheme.typography.labelMedium)
                                }
                            }
                        },
                        optionItem = { option, _ ->
                            Text(option, style = MaterialTheme.typography.labelSmall)
                        },
                    )
                },
            )
        }
    }
}
