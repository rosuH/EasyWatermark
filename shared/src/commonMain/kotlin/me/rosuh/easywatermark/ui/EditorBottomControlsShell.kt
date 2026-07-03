package me.rosuh.easywatermark.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

data class EditorBottomControlTab<T>(
    val label: String,
    val options: List<T>,
    val useCompactPadding: Boolean = false,
)

/**
 * Shared CMP bottom-controls host for the editor.
 *
 * Platform callers still provide resource-backed option models and option bodies; this shell owns
 * only tab/option selection state and the shared carousel/tab layout wiring.
 */
@Composable
fun <T> EditorBottomControlsShell(
    tabs: List<EditorBottomControlTab<T>>,
    modifier: Modifier = Modifier,
    optionControl: @Composable (option: T, modifier: Modifier) -> Unit,
    optionItem: @Composable (option: T) -> Unit,
    onIndicatorPosition: (startPx: Int, endPx: Int) -> Unit = { _, _ -> },
) {
    if (tabs.isEmpty()) {
        return
    }

    var selectedTabIndex by remember { mutableIntStateOf(0) }
    val selectedTab = tabs[selectedTabIndex.coerceIn(tabs.indices)]
    var selectedOption by remember(selectedTabIndex, selectedTab.options) {
        mutableStateOf(selectedTab.options.firstOrNull())
    }

    Column(modifier = modifier.fillMaxWidth()) {
        selectedOption?.let { option ->
            optionControl(
                option,
                Modifier
                    .fillMaxWidth()
                    .height(56.dp),
            )
        }
        EditorOptionCarousel(
            options = selectedTab.options,
            useCompactPadding = selectedTab.useCompactPadding,
            onOptionSelected = { selectedOption = it },
            itemContent = optionItem,
        )

        EditorBottomTabRow(
            selectedTabIndex = selectedTabIndex,
            labels = tabs.map { it.label },
            onTabSelected = { selectedTabIndex = it },
            onIndicatorPosition = onIndicatorPosition,
        )
    }
}
