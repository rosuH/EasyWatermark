package me.rosuh.easywatermark.ui.compose

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/**
 * Shared watermark **text** option.
 *
 * Product interaction (owner, Phase B):
 * 1. User taps the **Text** mode button in the Content carousel.
 * 2. A modal sheet opens with a text field to edit the watermark.
 * 3. **Template entry is top-end of the sheet** (not beside a permanent inline field).
 *
 * [openSignal]: incremented by the parent whenever the Text option is (re)activated via the
 * carousel. Sheet opens on each positive signal so re-tapping Text reopens the dialog.
 * `0` means "not opened by signal yet" (initial default selection shows summary only).
 */
data class TextContentOptionStrings(
    val templateIconContentDescription: String,
    val editSheetTitle: String,
    val confirmButton: String,
)

@Composable
fun TextContentOption(
    text: String,
    strings: TextContentOptionStrings,
    templateIcon: Painter? = null,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    /**
     * Bump when the Text carousel button is activated so the edit sheet opens.
     * Keep `0` for passive display of the current text summary.
     */
    openSignal: Int = 0,
    onTextChange: (String) -> Unit,
    onGoTemplateList: () -> Unit = {},
) {
    var showEditSheet by remember { mutableStateOf(false) }

    LaunchedEffect(openSignal) {
        if (openSignal > 0 && enabled) {
            showEditSheet = true
        }
    }

    // Summary row under the filmstrip: current text; tap re-opens the edit sheet.
    // Template entry lives only in the sheet (top-end), not on this row.
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.Start,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .weight(1f)
                .testTag(TEXT_CONTENT_ROW_TAG)
                .clickable(enabled = enabled) { showEditSheet = true }
                .padding(vertical = 12.dp),
        )
    }

    if (showEditSheet) {
        WatermarkTextEditSheet(
            initialText = text,
            strings = strings,
            templateIcon = templateIcon,
            enabled = enabled,
            onConfirm = {
                onTextChange(it)
                showEditSheet = false
            },
            onDismiss = { showEditSheet = false },
            onGoTemplateList = {
                // Leave sheet open or dismiss — templates host is separate; dismiss first for focus.
                showEditSheet = false
                onGoTemplateList()
            },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun WatermarkTextEditSheet(
    initialText: String,
    strings: TextContentOptionStrings,
    templateIcon: Painter?,
    enabled: Boolean,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
    onGoTemplateList: () -> Unit,
) {
    var draft by remember { mutableStateOf(initialText) }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        shape = RectangleShape,
        containerColor = MaterialTheme.colorScheme.surface,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .navigationBarsPadding()
                .padding(bottom = 20.dp),
        ) {
            // Title row + template entry (top-end / 右上角).
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 16.dp, bottom = 12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = strings.editSheetTitle,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f),
                )
                if (templateIcon != null) {
                    IconButton(
                        onClick = onGoTemplateList,
                        enabled = enabled,
                        modifier = Modifier.testTag(TEXT_CONTENT_TEMPLATE_ICON_TAG),
                    ) {
                        Icon(
                            painter = templateIcon,
                            contentDescription = strings.templateIconContentDescription,
                        )
                    }
                }
            }
            OutlinedTextField(
                value = draft,
                onValueChange = { draft = it },
                enabled = enabled,
                modifier = Modifier
                    .fillMaxWidth()
                    .wrapContentHeight()
                    .testTag(TEXT_CONTENT_EDIT_FIELD_TAG),
                shape = RectangleShape,
            )
            Button(
                onClick = { onConfirm(draft) },
                enabled = enabled,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 24.dp)
                    .testTag(TEXT_CONTENT_CONFIRM_TAG),
                shape = RectangleShape,
            ) {
                Text(text = strings.confirmButton)
            }
        }
    }
}

/** Stable Compose testTag ids for XCUITest (not user-facing accessibility strings). */
const val TEXT_CONTENT_ROW_TAG = "watermarkTextContent"
const val TEXT_CONTENT_EDIT_FIELD_TAG = "watermarkTextEditField"
const val TEXT_CONTENT_CONFIRM_TAG = "watermarkTextConfirm"
const val TEXT_CONTENT_TEMPLATE_ICON_TAG = "watermarkTextTemplateIcon"
