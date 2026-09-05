package me.rosuh.easywatermark.ui

import androidx.compose.material3.ExperimentalMaterial3Api

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import me.rosuh.easywatermark.ui.compose.EwmContentDialog
import me.rosuh.easywatermark.ui.compose.EwmModalBottomSheet
import me.rosuh.easywatermark.ui.compose.FontPanel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditorFontSheetHost(
    state: FontPanelUiState,
    onEvent: (FontPanelEvent) -> Unit,
    useLargeDialog: Boolean,
    sampleFamilies: Map<String, FontFamily> = emptyMap(),
    onVisibilityChange: (Boolean) -> Unit = {},
    openRequest: Int = 0,
    content: @Composable (showFontSheet: () -> Unit) -> Unit,
) {
    var show by remember { mutableStateOf(false) }

    LaunchedEffect(openRequest) {
        if (openRequest > 0 && !show) {
            show = true
            onVisibilityChange(true)
        }
    }

    content {
        if (!show) {
            show = true
            onVisibilityChange(true)
        }
    }

    if (show) {
        val dismiss = {
            show = false
            onVisibilityChange(false)
            onEvent(FontPanelEvent.Dismiss)
        }
        if (useLargeDialog) {
            EwmContentDialog(
                onDismissRequest = dismiss,
                maxWidth = 560.dp,
                testTag = "editorFontDialog",
                scrollContent = false,
            ) {
                FontPanel(
                    state = state,
                    onEvent = { event ->
                        if (event is FontPanelEvent.Dismiss) {
                            dismiss()
                        } else {
                            onEvent(event)
                        }
                    },
                    useLargeDialog = true,
                    sampleFamilies = sampleFamilies,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 720.dp),
                )
            }
        } else {
            EwmModalBottomSheet(onDismissRequest = dismiss) {
                FontPanel(
                    state = state,
                    onEvent = { event ->
                        if (event is FontPanelEvent.Dismiss) {
                            dismiss()
                        } else {
                            onEvent(event)
                        }
                    },
                    useLargeDialog = false,
                    sampleFamilies = sampleFamilies,
                )
            }
        }
    }
}
