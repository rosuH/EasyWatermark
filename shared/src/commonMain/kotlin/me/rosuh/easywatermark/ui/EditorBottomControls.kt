package me.rosuh.easywatermark.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.platform.testTag
import kotlin.math.roundToInt
import me.rosuh.easywatermark.data.model.FuncType
import me.rosuh.easywatermark.data.model.MediaRef
import me.rosuh.easywatermark.data.model.TextTypeface
import me.rosuh.easywatermark.data.model.WaterMark
import me.rosuh.easywatermark.data.model.WatermarkConfigChange
import me.rosuh.easywatermark.data.model.WatermarkConfigRules
import me.rosuh.easywatermark.data.model.WatermarkTileMode
import me.rosuh.easywatermark.shared.generated.resources.Res
import me.rosuh.easywatermark.shared.generated.resources.title_content
import me.rosuh.easywatermark.shared.generated.resources.title_layout
import me.rosuh.easywatermark.shared.generated.resources.title_style
import me.rosuh.easywatermark.ui.compose.SliderOption
import me.rosuh.easywatermark.ui.compose.TextContentOption
import me.rosuh.easywatermark.ui.compose.TextTypeface as TextTypefaceOption
import me.rosuh.easywatermark.ui.compose.TileMode as TileModeOption
import org.jetbrains.compose.resources.stringResource

/**
 * Shared editor bottom controls (U1). S-i18n-2: tab/option labels from [Res].
 */
@Composable
fun EditorBottomControls(
    waterMark: WaterMark,
    templateIcon: Painter?,
    onValueChange: (WatermarkConfigChange) -> Unit,
    onGoTemplateList: () -> Unit,
    colorOption: @Composable (
        modifier: Modifier,
        waterMark: WaterMark,
        onColor: (Int) -> Unit,
    ) -> Unit,
    iconOption: @Composable (
        modifier: Modifier,
        waterMark: WaterMark,
        onIcon: (MediaRef) -> Unit,
    ) -> Unit,
    optionItem: @Composable (spec: EditorOptionSpec, selected: Boolean) -> Unit,
    modifier: Modifier = Modifier,
    contentOptions: List<EditorOptionSpec> = EditorOptionCatalog.content,
    styleOptions: List<EditorOptionSpec> = EditorOptionCatalog.style,
    layoutOptions: List<EditorOptionSpec> = EditorOptionCatalog.layout,
    onIndicatorPosition: (startPx: Int, endPx: Int) -> Unit = { _, _ -> },
) {
    val contentLabel = stringResource(Res.string.title_content)
    val styleLabel = stringResource(Res.string.title_style)
    val layoutLabel = stringResource(Res.string.title_layout)

    EditorBottomControlsShell(
        tabs = listOf(
            EditorBottomControlTab(label = contentLabel, options = contentOptions),
            EditorBottomControlTab(
                label = styleLabel,
                options = styleOptions,
                useCompactPadding = true,
            ),
            EditorBottomControlTab(label = layoutLabel, options = layoutOptions),
        ),
        modifier = modifier,
        optionControl = { spec, optionModifier, optionActivationSignal ->
            EditorOptionControl(
                spec = spec,
                waterMark = waterMark,
                templateIcon = templateIcon,
                optionActivationSignal = optionActivationSignal,
                onValueChange = onValueChange,
                onGoTemplateList = onGoTemplateList,
                colorOption = colorOption,
                iconOption = iconOption,
                modifier = optionModifier,
            )
        },
        optionItem = optionItem,
        // Only Text opens a modal on select; Icon must not bump the signal or Text flashes on exit.
        shouldSignalActivation = { it.type == FuncType.Text },
        onIndicatorPosition = onIndicatorPosition,
    )
}

@Composable
private fun EditorOptionControl(
    spec: EditorOptionSpec,
    waterMark: WaterMark,
    templateIcon: Painter?,
    optionActivationSignal: Int,
    onValueChange: (WatermarkConfigChange) -> Unit,
    onGoTemplateList: () -> Unit,
    colorOption: @Composable (Modifier, WaterMark, (Int) -> Unit) -> Unit,
    iconOption: @Composable (Modifier, WaterMark, (MediaRef) -> Unit) -> Unit,
    modifier: Modifier = Modifier,
) {
    EditorOptionControlFrame(
        modifier = modifier.testTag("editorControl-${spec.type.stableKey()}"),
    ) { innerModifier ->
        when (spec.type) {
            FuncType.Alpha -> {
                SliderOption(
                    modifier = innerModifier,
                    currentValue = WatermarkConfigRules.alphaByteToPercent(waterMark.alpha),
                    valueRange = spec.valueRange,
                    // I2: product name for slider semantics.
                    label = spec.type.label(),
                    // F2: emit typed command at control source (0..100 percent).
                    onValueChange = { onValueChange(WatermarkConfigChange.AlphaPercent(it)) },
                )
            }

            FuncType.TextSize -> {
                SliderOption(
                    modifier = innerModifier,
                    currentValue = waterMark.textSize,
                    valueRange = spec.valueRange,
                    label = spec.type.label(),
                    onValueChange = { onValueChange(WatermarkConfigChange.TextSize(it)) },
                )
            }

            FuncType.Vertical -> {
                SliderOption(
                    modifier = innerModifier,
                    currentValue = waterMark.vGap.toFloat(),
                    valueRange = spec.valueRange,
                    label = spec.type.label(),
                    // Preserve legacy (Float).roundToInt() at emission.
                    onValueChange = {
                        onValueChange(WatermarkConfigChange.VerticalGap(it.roundToInt()))
                    },
                )
            }

            FuncType.Horizon -> {
                SliderOption(
                    modifier = innerModifier,
                    currentValue = waterMark.hGap.toFloat(),
                    valueRange = spec.valueRange,
                    label = spec.type.label(),
                    onValueChange = {
                        onValueChange(WatermarkConfigChange.HorizontalGap(it.roundToInt()))
                    },
                )
            }

            FuncType.Degree -> {
                SliderOption(
                    modifier = innerModifier,
                    currentValue = waterMark.degree,
                    valueRange = spec.valueRange,
                    label = spec.type.label(),
                    onValueChange = { onValueChange(WatermarkConfigChange.Degree(it)) },
                )
            }

            FuncType.Color -> {
                colorOption(innerModifier, waterMark) { color ->
                    onValueChange(WatermarkConfigChange.Color(color))
                }
            }

            FuncType.Icon -> {
                iconOption(innerModifier, waterMark) { ref ->
                    onValueChange(WatermarkConfigChange.Icon(ref))
                }
            }

            FuncType.Text -> {
                TextContentOption(
                    text = waterMark.text,
                    templateIcon = templateIcon,
                    modifier = innerModifier,
                    openSignal = optionActivationSignal,
                    onTextChange = { onValueChange(WatermarkConfigChange.Text(it)) },
                    onGoTemplateList = onGoTemplateList,
                )
            }

            FuncType.TextTypeFace -> {
                TextTypefaceOption(
                    typeface = waterMark.textTypeface,
                    modifier = innerModifier,
                    onValueChange = { next: TextTypeface ->
                        onValueChange(WatermarkConfigChange.Typeface(next))
                    },
                )
            }

            FuncType.TileMode -> {
                TileModeOption(
                    mode = waterMark.tileMode,
                    modifier = innerModifier,
                    onValueChange = { next: WatermarkTileMode ->
                        onValueChange(WatermarkConfigChange.TileMode(next))
                    },
                )
            }
        }
    }
}
