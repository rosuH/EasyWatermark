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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
 * Shared (commonMain) text-content option for the watermark editor.
 *
 * S4d-238 resource strategy: all text and the template-list icon are passed from the caller.
 * Android resolves `stringResource`/`painterResource` in [EditorScreen.kt]; Desktop/iOS pass
 * hard-coded/localized strings and a Painter. This composable has no `R`, `stringResource`,
 * `painterResource`, `Preview`, `FuncTitleModel`, `FuncType`, `WaterMark`, or Android imports.
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
    onTextChange: (String) -> Unit,
    onGoTemplateList: () -> Unit = {},
) {
    var showEditSheet by remember { mutableStateOf(false) }
    // Parity (ADR-0011 / ADR-0015 item B): production opens a modal "Edit watermark"
    // sheet when the text row is tapped, instead of an inline always-editable field.
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
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
                .padding(vertical = 16.dp)
        )
        if (templateIcon != null) {
            Icon(
                painter = templateIcon,
                contentDescription = strings.templateIconContentDescription,
                modifier = Modifier
                    .clickable(enabled = enabled) { onGoTemplateList() }
                    .padding(start = 16.dp)
            )
        }
    }

    if (showEditSheet) {
        WatermarkTextEditSheet(
            initialText = text,
            strings = strings,
            enabled = enabled,
            onConfirm = {
                onTextChange(it)
                showEditSheet = false
            },
            onDismiss = { showEditSheet = false }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun WatermarkTextEditSheet(
    initialText: String,
    strings: TextContentOptionStrings,
    enabled: Boolean,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
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
                .padding(bottom = 20.dp)
        ) {
            Text(
                text = strings.editSheetTitle,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(top = 24.dp, bottom = 12.dp)
            )
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
