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
import me.rosuh.easywatermark.data.model.MediaRef
import me.rosuh.easywatermark.data.model.WaterMark
import me.rosuh.easywatermark.data.model.WatermarkConfigChange
import me.rosuh.easywatermark.shared.generated.resources.Res
import me.rosuh.easywatermark.shared.generated.resources.title_content
import me.rosuh.easywatermark.shared.generated.resources.title_layout
import me.rosuh.easywatermark.shared.generated.resources.title_style
import org.jetbrains.compose.resources.stringResource

/**
 * Expanded/Wide supporting-pane inspector (form chrome).
 *
 * Top Content/Style/Layout tabs + top-aligned scroll body with **all** fields for the
 * selected tab. Does not use [EditorOptionControlFrame] Center void or the phone carousel.
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
    contentOptions: List<EditorOptionSpec> = EditorOptionCatalog.content,
    styleOptions: List<EditorOptionSpec> = EditorOptionCatalog.style,
    layoutOptions: List<EditorOptionSpec> = EditorOptionCatalog.layout,
) {
    val tabs = listOf(
        stringResource(Res.string.title_content) to contentOptions,
        stringResource(Res.string.title_style) to styleOptions,
        stringResource(Res.string.title_layout) to layoutOptions,
    )
    var selectedTabIndex by remember { mutableIntStateOf(0) }
    val safeTabIndex = selectedTabIndex.coerceIn(tabs.indices)
    val selectedOptions = tabs[safeTabIndex].second
    val scroll = rememberScrollState()

    Column(
        modifier = modifier
            .fillMaxSize()
            .testTag("editorInspectorPanel"),
        horizontalAlignment = Alignment.Start,
    ) {
        EditorBottomTabRow(
            selectedTabIndex = safeTabIndex,
            labels = tabs.map { it.first },
            onTabSelected = { index ->
                if (index in tabs.indices) selectedTabIndex = index
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
            selectedOptions.forEach { spec ->
                EditorInspectorField(
                    spec = spec,
                    waterMark = waterMark,
                    templateIcon = templateIcon,
                    onValueChange = onValueChange,
                    onGoTemplateList = onGoTemplateList,
                    colorOption = colorOption,
                    iconOption = iconOption,
                )
            }
        }
    }
}

@Composable
private fun EditorInspectorField(
    spec: EditorOptionSpec,
    waterMark: WaterMark,
    templateIcon: Painter?,
    onValueChange: (WatermarkConfigChange) -> Unit,
    onGoTemplateList: () -> Unit,
    colorOption: @Composable (Modifier, WaterMark, (Int) -> Unit) -> Unit,
    iconOption: @Composable (Modifier, WaterMark, (MediaRef) -> Unit) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("editorInspectorField-${spec.type.stableKey()}"),
        verticalArrangement = Arrangement.spacedBy(6.dp),
        horizontalAlignment = Alignment.Start,
    ) {
        Text(
            text = spec.type.label(),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.fillMaxWidth(),
        )
        // Form rows: no Center frame; Text openSignal stays 0 (tap-to-edit only).
        EditorOptionControl(
            spec = spec,
            waterMark = waterMark,
            templateIcon = templateIcon,
            optionActivationSignal = 0,
            onValueChange = onValueChange,
            onGoTemplateList = onGoTemplateList,
            colorOption = colorOption,
            iconOption = iconOption,
            framed = false,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}
