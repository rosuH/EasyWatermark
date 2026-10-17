package me.rosuh.easywatermark.ui

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import me.rosuh.easywatermark.ui.compose.EwmContentDialog
import me.rosuh.easywatermark.ui.compose.EwmModalBottomSheet
import me.rosuh.easywatermark.ui.compose.FontPanel
import me.rosuh.easywatermark.ui.theme.EwmTheme
import me.rosuh.easywatermark.ui.theme.currentMotionPolicy
import me.rosuh.easywatermark.ui.theme.motionDurationMs

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
    val scope = rememberCoroutineScope()
    val latestOnEvent by rememberUpdatedState(onEvent)
    val latestOnVisibility by rememberUpdatedState(onVisibilityChange)
    val sheetMotionMs = motionDurationMs(
        currentMotionPolicy(),
        EwmTheme.motion.optionPanelSlideMs,
    )

    fun open() {
        if (!show) {
            show = true
            latestOnVisibility(true)
        }
    }

    LaunchedEffect(openRequest) {
        if (openRequest > 0 && !show) {
            show = true
            latestOnVisibility(true)
        }
    }

    content { open() }

    if (!show) {
        return
    }

    val finishDismiss: () -> Unit = {
        if (show) {
            show = false
            latestOnVisibility(false)
            latestOnEvent(FontPanelEvent.Dismiss)
        }
    }

    if (useLargeDialog) {
        var closeRequest by remember { mutableIntStateOf(0) }
        EwmContentDialog(
            onDismissRequest = finishDismiss,
            maxWidth = 560.dp,
            testTag = "editorFontDialog",
            scrollContent = false,
            closeRequest = closeRequest,
        ) {
            FontPanel(
                state = state,
                onEvent = { event ->
                    if (event is FontPanelEvent.Dismiss) {
                        closeRequest += 1
                    } else {
                        latestOnEvent(event)
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
        val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        var hiding by remember { mutableStateOf(false) }
        val requestSheetDismiss: () -> Unit = {
            if (!hiding) {
                if (sheetMotionMs <= 0) {
                    finishDismiss()
                } else {
                    hiding = true
                    scope.launch {
                        try {
                            sheetState.hide()
                            if (!sheetState.isVisible) finishDismiss()
                        } finally {
                            hiding = false
                        }
                    }
                }
            }
        }
        EwmModalBottomSheet(
            onDismissRequest = finishDismiss,
            sheetState = sheetState,
        ) {
            FontPanel(
                state = state,
                onEvent = { event ->
                    if (event is FontPanelEvent.Dismiss) {
                        requestSheetDismiss()
                    } else {
                        latestOnEvent(event)
                    }
                },
                useLargeDialog = false,
                sampleFamilies = sampleFamilies,
            )
        }
    }
}
