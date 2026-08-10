package me.rosuh.easywatermark.ui.compose

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import me.rosuh.easywatermark.ui.theme.EwmTheme

/**
 * Centered product dialog for ≥840 surfaces (export / template).
 * Panel chrome matches [EwmModalBottomSheet] / [EwmConfirmDialog].
 */
@Composable
fun EwmContentDialog(
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    maxWidth: Dp = 720.dp,
    maxHeight: Dp = 720.dp,
    properties: DialogProperties = DialogProperties(usePlatformDefaultWidth = false),
    testTag: String = "ewmContentDialog",
    content: @Composable () -> Unit,
) {
    Dialog(
        onDismissRequest = onDismissRequest,
        properties = properties,
    ) {
        Surface(
            modifier = modifier
                .widthIn(max = maxWidth)
                .heightIn(max = maxHeight)
                .fillMaxWidth()
                .testTag(testTag),
            shape = EwmTheme.panel.dialogShape,
            color = EwmTheme.panel.containerColor,
            tonalElevation = EwmTheme.panel.tonalElevation,
        ) {
            // Scroll when content exceeds max height — avoids clipped CTAs on short windows.
            androidx.compose.foundation.layout.Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
            ) {
                content()
            }
        }
    }
}
