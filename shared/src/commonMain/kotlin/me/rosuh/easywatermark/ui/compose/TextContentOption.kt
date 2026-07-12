package me.rosuh.easywatermark.ui.compose

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/**
 * Shared (commonMain) text-content option for the watermark editor.
 *
 * S4d-238 resource strategy: all text and the template-list icon are passed from the caller.
 *
 * [inlineEditable]:
 * - `true` (Android Phase B default): always-visible live [TextField] matching production v2.10.0.
 * - `false` (iOS XCUITest / sheet contract): tappable row opens "Edit watermark" modal with Confirm.
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
    inlineEditable: Boolean = true,
    onTextChange: (String) -> Unit,
    onGoTemplateList: () -> Unit = {},
) {
    if (inlineEditable) {
        InlineTextContentRow(
            text = text,
            strings = strings,
            templateIcon = templateIcon,
            modifier = modifier,
            enabled = enabled,
            onTextChange = onTextChange,
            onGoTemplateList = onGoTemplateList,
        )
    } else {
        SheetTextContentRow(
            text = text,
            strings = strings,
            templateIcon = templateIcon,
            modifier = modifier,
            enabled = enabled,
            onTextChange = onTextChange,
            onGoTemplateList = onGoTemplateList,
        )
    }
}

@Composable
private fun InlineTextContentRow(
    text: String,
    strings: TextContentOptionStrings,
    templateIcon: Painter?,
    modifier: Modifier,
    enabled: Boolean,
    onTextChange: (String) -> Unit,
    onGoTemplateList: () -> Unit,
) {
    val focusManager = LocalFocusManager.current
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TextField(
            value = text,
            onValueChange = onTextChange,
            enabled = enabled,
            singleLine = true,
            textStyle = MaterialTheme.typography.bodyLarge,
            modifier = Modifier
                .weight(1f)
                .testTag(TEXT_CONTENT_ROW_TAG),
            shape = RectangleShape,
            colors = TextFieldDefaults.colors(
                focusedContainerColor = Color.Transparent,
                unfocusedContainerColor = Color.Transparent,
                disabledContainerColor = Color.Transparent,
                focusedIndicatorColor = MaterialTheme.colorScheme.primary,
                unfocusedIndicatorColor = MaterialTheme.colorScheme.outline,
            ),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(
                onDone = { focusManager.clearFocus() },
            ),
        )
        TemplateIcon(
            templateIcon = templateIcon,
            contentDescription = strings.templateIconContentDescription,
            enabled = enabled,
            onGoTemplateList = onGoTemplateList,
        )
    }
}

@Composable
private fun SheetTextContentRow(
    text: String,
    strings: TextContentOptionStrings,
    templateIcon: Painter?,
    modifier: Modifier,
    enabled: Boolean,
    onTextChange: (String) -> Unit,
    onGoTemplateList: () -> Unit,
) {
    var showEditSheet by remember { mutableStateOf(false) }
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.SpaceBetween,
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
        TemplateIcon(
            templateIcon = templateIcon,
            contentDescription = strings.templateIconContentDescription,
            enabled = enabled,
            onGoTemplateList = onGoTemplateList,
        )
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
            onDismiss = { showEditSheet = false },
        )
    }
}

@Composable
private fun TemplateIcon(
    templateIcon: Painter?,
    contentDescription: String,
    enabled: Boolean,
    onGoTemplateList: () -> Unit,
) {
    if (templateIcon == null) return
    Icon(
        painter = templateIcon,
        contentDescription = contentDescription,
        modifier = Modifier
            .clickable(enabled = enabled) { onGoTemplateList() }
            .padding(start = 8.dp)
            .testTag(TEXT_CONTENT_TEMPLATE_ICON_TAG),
    )
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
                .padding(bottom = 20.dp),
        ) {
            Text(
                text = strings.editSheetTitle,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(top = 24.dp, bottom = 12.dp),
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
const val TEXT_CONTENT_TEMPLATE_ICON_TAG = "watermarkTextTemplateIcon"
