package me.rosuh.easywatermark.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import me.rosuh.easywatermark.data.model.FuncType
import me.rosuh.easywatermark.data.model.MediaRef
import me.rosuh.easywatermark.data.model.WaterMark
import me.rosuh.easywatermark.data.model.WatermarkConfigChange
import me.rosuh.easywatermark.data.model.WatermarkMode
import me.rosuh.easywatermark.shared.generated.resources.Res
import me.rosuh.easywatermark.shared.generated.resources.inspector_section_gaps
import me.rosuh.easywatermark.shared.generated.resources.inspector_section_look
import me.rosuh.easywatermark.shared.generated.resources.inspector_section_mode
import me.rosuh.easywatermark.shared.generated.resources.inspector_section_tile
import me.rosuh.easywatermark.shared.generated.resources.title_content
import me.rosuh.easywatermark.shared.generated.resources.title_layout
import me.rosuh.easywatermark.shared.generated.resources.title_style
import me.rosuh.easywatermark.shared.generated.resources.water_mark_mode_image
import me.rosuh.easywatermark.shared.generated.resources.water_mark_mode_text
import me.rosuh.easywatermark.ui.compose.DesignChoiceChips
import me.rosuh.easywatermark.ui.compose.DesignChoiceOption
import org.jetbrains.compose.resources.stringResource

/**
 * Expanded/Wide supporting-pane inspector — DEMO form morphology.
 *
 * Top Content/Style/Layout tabs + top-aligned scroll body.
 * Content: equal-width Text|Icon segment, only active-mode fields, inline text edit.
 * Style/Layout: labeled form sliders (left label + mono value).
 *
 * Compact/Medium keep [EditorBottomControls].
 */
@Composable
fun EditorInspectorPanel(
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
    modifier: Modifier = Modifier,
    /**
     * Optional E2E initial tab (0=Content, 1=Style, 2=Layout). Desktop `-Dewm.desktop.inspectorTab`.
     */
    initialTabIndex: Int = 0,
    contentOptions: List<EditorOptionSpec> = EditorOptionCatalog.content,
    styleOptions: List<EditorOptionSpec> = EditorOptionCatalog.style,
    layoutOptions: List<EditorOptionSpec> = EditorOptionCatalog.layout,
) {
    val tabLabels = listOf(
        stringResource(Res.string.title_content),
        stringResource(Res.string.title_style),
        stringResource(Res.string.title_layout),
    )
    var selectedTabIndex by remember {
        mutableIntStateOf(initialTabIndex.coerceIn(0, tabLabels.lastIndex))
    }
    val safeTabIndex = selectedTabIndex.coerceIn(0, tabLabels.lastIndex)
    val scroll = rememberScrollState()

    // Spec lookup for ranges (style/layout catalogs).
    val specByType = remember(contentOptions, styleOptions, layoutOptions) {
        (contentOptions + styleOptions + layoutOptions).associateBy { it.type }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .testTag("editorInspectorPanel"),
        horizontalAlignment = Alignment.Start,
    ) {
        EditorBottomTabRow(
            selectedTabIndex = safeTabIndex,
            labels = tabLabels,
            onTabSelected = { index ->
                if (index in tabLabels.indices) selectedTabIndex = index
            },
            modifier = Modifier
                .fillMaxWidth()
                .testTag("editorInspectorTabs"),
        )

        Column(
            modifier = Modifier
                .weight(1f, fill = true)
                .fillMaxWidth()
                .verticalScroll(scroll)
                .padding(top = 12.dp, bottom = 16.dp)
                .testTag("editorInspectorForm"),
            verticalArrangement = Arrangement.spacedBy(14.dp),
            horizontalAlignment = Alignment.Start,
        ) {
            when (safeTabIndex) {
                0 -> ContentForm(
                    waterMark = waterMark,
                    templateIcon = templateIcon,
                    onValueChange = onValueChange,
                    onGoTemplateList = onGoTemplateList,
                    iconOption = iconOption,
                    specByType = specByType,
                )
                1 -> StyleForm(
                    waterMark = waterMark,
                    onValueChange = onValueChange,
                    colorOption = colorOption,
                    styleOptions = styleOptions,
                )
                else -> LayoutForm(
                    waterMark = waterMark,
                    onValueChange = onValueChange,
                    layoutOptions = layoutOptions,
                )
            }
        }
    }
}

@Composable
private fun ContentForm(
    waterMark: WaterMark,
    templateIcon: Painter?,
    onValueChange: (WatermarkConfigChange) -> Unit,
    onGoTemplateList: () -> Unit,
    iconOption: @Composable (Modifier, WaterMark, (MediaRef) -> Unit) -> Unit,
    specByType: Map<FuncType, EditorOptionSpec>,
) {
    val textLabel = stringResource(Res.string.water_mark_mode_text)
    val iconLabel = stringResource(Res.string.water_mark_mode_image)

    FormSectionLabel(stringResource(Res.string.inspector_section_mode))
    DesignChoiceChips(
        options = listOf(
            DesignChoiceOption(label = textLabel, value = WatermarkMode.Text),
            DesignChoiceOption(label = iconLabel, value = WatermarkMode.Image),
        ),
        selected = waterMark.markMode,
        onSelected = { mode ->
            if (mode != waterMark.markMode) {
                onValueChange(WatermarkConfigChange.MarkMode(mode))
            }
        },
        equalWidth = true,
        modifier = Modifier
            .fillMaxWidth()
            .testTag("editorInspectorModeSegment"),
    )

    EditorInspectorFormFields.contentFields(waterMark.markMode).forEach { type ->
        val spec = specByType[type] ?: EditorOptionSpec(type)
        when (type) {
            FuncType.Text -> {
                // Title + template icon are owned by InlineTextContentField (same row).
                EditorOptionControl(
                    spec = spec,
                    waterMark = waterMark,
                    templateIcon = templateIcon,
                    optionActivationSignal = 0,
                    onValueChange = onValueChange,
                    onGoTemplateList = onGoTemplateList,
                    colorOption = { _, _, _ -> },
                    iconOption = iconOption,
                    framed = false,
                    formPath = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            FuncType.Icon -> {
                FormFieldLabel(iconLabel)
                EditorOptionControl(
                    spec = spec,
                    waterMark = waterMark,
                    templateIcon = templateIcon,
                    optionActivationSignal = 0,
                    onValueChange = onValueChange,
                    onGoTemplateList = onGoTemplateList,
                    colorOption = { _, _, _ -> },
                    iconOption = iconOption,
                    framed = false,
                    formPath = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            else -> Unit
        }
    }
}

@Composable
private fun StyleForm(
    waterMark: WaterMark,
    onValueChange: (WatermarkConfigChange) -> Unit,
    colorOption: @Composable (Modifier, WaterMark, (Int) -> Unit) -> Unit,
    styleOptions: List<EditorOptionSpec>,
) {
    // DEMO rhythm: 平铺 (tile) → size/alpha/degree → 外观 (typeface/color)
    val tile = styleOptions.filter { it.type == FuncType.TileMode }
    val metrics = styleOptions.filter {
        it.type == FuncType.TextSize || it.type == FuncType.Alpha || it.type == FuncType.Degree
    }
    val appearance = styleOptions.filter {
        it.type == FuncType.TextTypeFace || it.type == FuncType.Color
    }

    if (tile.isNotEmpty()) {
        FormSectionLabel(stringResource(Res.string.inspector_section_tile))
        tile.forEach { spec ->
            FormOptionBody(
                spec = spec,
                waterMark = waterMark,
                onValueChange = onValueChange,
                colorOption = colorOption,
                showOuterLabel = false,
            )
        }
    }
    metrics.forEach { spec ->
        FormOptionBody(
            spec = spec,
            waterMark = waterMark,
            onValueChange = onValueChange,
            colorOption = colorOption,
            showOuterLabel = false, // slider carries left label
        )
    }
    if (appearance.isNotEmpty()) {
        FormSectionLabel(stringResource(Res.string.inspector_section_look))
        appearance.forEach { spec ->
            FormOptionBody(
                spec = spec,
                waterMark = waterMark,
                onValueChange = onValueChange,
                colorOption = colorOption,
                showOuterLabel = spec.type == FuncType.Color,
            )
        }
    }
}

@Composable
private fun LayoutForm(
    waterMark: WaterMark,
    onValueChange: (WatermarkConfigChange) -> Unit,
    layoutOptions: List<EditorOptionSpec>,
) {
    FormSectionLabel(stringResource(Res.string.inspector_section_gaps))
    layoutOptions.forEach { spec ->
        FormOptionBody(
            spec = spec,
            waterMark = waterMark,
            onValueChange = onValueChange,
            colorOption = { _, _, _ -> },
            showOuterLabel = false,
        )
    }
}

@Composable
private fun FormOptionBody(
    spec: EditorOptionSpec,
    waterMark: WaterMark,
    onValueChange: (WatermarkConfigChange) -> Unit,
    colorOption: @Composable (Modifier, WaterMark, (Int) -> Unit) -> Unit,
    showOuterLabel: Boolean,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("editorInspectorField-${spec.type.stableKey()}"),
        verticalArrangement = Arrangement.spacedBy(6.dp),
        horizontalAlignment = Alignment.Start,
    ) {
        if (showOuterLabel) {
            FormFieldLabel(spec.type.label())
        }
        EditorOptionControl(
            spec = spec,
            waterMark = waterMark,
            templateIcon = null,
            optionActivationSignal = 0,
            onValueChange = onValueChange,
            onGoTemplateList = {},
            colorOption = colorOption,
            iconOption = { _, _, _ -> },
            framed = false,
            formPath = true,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun FormSectionLabel(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
        letterSpacing = 0.8.sp,
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 2.dp)
            .testTag("editorInspectorSection"),
    )
}

@Composable
private fun FormFieldLabel(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.fillMaxWidth(),
    )
}
