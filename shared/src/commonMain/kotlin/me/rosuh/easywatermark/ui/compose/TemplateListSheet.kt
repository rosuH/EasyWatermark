package me.rosuh.easywatermark.ui.compose

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import me.rosuh.easywatermark.data.model.entity.Template
import me.rosuh.easywatermark.shared.generated.resources.Res
import me.rosuh.easywatermark.shared.generated.resources.dialog_button_add_template
import me.rosuh.easywatermark.shared.generated.resources.dialog_title_edit_watermark
import me.rosuh.easywatermark.shared.generated.resources.dialog_title_exist_confirm
import me.rosuh.easywatermark.shared.generated.resources.dialog_title_template_title
import me.rosuh.easywatermark.shared.generated.resources.template_delete
import me.rosuh.easywatermark.shared.generated.resources.template_edit
import me.rosuh.easywatermark.shared.generated.resources.tips_cancel_dialog
import me.rosuh.easywatermark.shared.generated.resources.tips_confirm_dialog
import me.rosuh.easywatermark.shared.generated.resources.tips_delete_template
import me.rosuh.easywatermark.shared.generated.resources.tips_list_empty
import me.rosuh.easywatermark.shared.generated.resources.tips_use_this_template
import org.jetbrains.compose.resources.stringResource
import kotlin.time.Clock

/**
 * Shared (commonMain) replacement for the legacy text-template surface —
 * `TextContentTemplateListFragment` (the list) + `EditTemplateContentFragment` (add/edit).
 * View→Compose migration, ADR-0016. Moved to commonMain in .
 *
 * The template CRUD already lives in [me.rosuh.easywatermark.ui.MainViewModel]
 * (`templateListFlow` + add/update/delete); this is a pure UI port over those callbacks.
 * "Use" applies the template content via the same `updateText` path the text editor uses,
 * So it doesn't depend on the legacy `UiState.UseTemplate` plumbing. *
 * S-i18n-2: labels from composeResources Res (the Android
 * caller resolves `stringResource` at the edge); both icons are passed as [Painter] parameters
 * (the Android caller resolves `painterResource` at the edge). This composable has no
 * `R.string`/`R.drawable`/`stringResource`/`painterResource` dependencies.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TemplateListSheet(
    templates: List<Template>,
    editIcon: Painter? = null,
    deleteIcon: Painter? = null,
    enabled: Boolean = true,
    newTemplateInitialText: String = "",
    /** ≥840: centered dialog; Compact/Medium keep bottom sheet. */
    useLargeDialog: Boolean = false,
    onDismiss: () -> Unit,
    onUse: (Template) -> Unit,
    onAdd: (String) -> Unit,
    onUpdate: (Template) -> Unit,
    onDelete: (Template) -> Unit,
) {
    var editTarget by remember { mutableStateOf<TemplateEditTarget?>(null) }
    var confirmUse by remember { mutableStateOf<Template?>(null) }
    var confirmDelete by remember { mutableStateOf<Template?>(null) }
    var selectedTemplateId by remember { mutableStateOf<Int?>(null) }

    val body: @Composable () -> Unit = {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .then(if (useLargeDialog) Modifier else Modifier.navigationBarsPadding())
                .padding(bottom = 20.dp)
                .testTag(TEMPLATE_LIST_SHEET_TAG),
        ) {
            // Header: title + add
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = stringResource(Res.string.dialog_title_template_title),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                TextButton(
                    enabled = enabled,
                    onClick = { editTarget = TemplateEditTarget(null) },
                    modifier = Modifier.testTag(TEMPLATE_ADD_BUTTON_TAG),
                ) {
                    Text(text = stringResource(Res.string.dialog_button_add_template))
                }
            }

            if (templates.isEmpty()) {
                Text(
                    text = stringResource(Res.string.tips_list_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 32.dp),
                )
            } else {
                LazyColumn(modifier = Modifier.heightIn(max = 360.dp)) {
                    items(templates, key = { it.id }) { template ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = template.content ?: "",
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurface,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier
                                    .weight(1f)
                                    .testTag(templateRowTag(template.id))
                                    .selectable(
                                        selected = selectedTemplateId == template.id,
                                        enabled = enabled,
                                    ) {
                                        selectedTemplateId = template.id
                                        confirmUse = template
                                    }
                                    .semantics {
                                        contentDescription = template.content ?: ""
                                    }
                                    .padding(vertical = 16.dp)
                            )
                            if (editIcon != null) {
                                IconButton(
                                    enabled = enabled,
                                    onClick = { editTarget = TemplateEditTarget(template) },
                                    modifier = Modifier.testTag(templateEditButtonTag(template.id)),
                                ) {
                                    Icon(
                                        painter = editIcon,
                                        contentDescription = stringResource(Res.string.dialog_title_edit_watermark),
                                    )
                                }
                            } else {
                                TextButton(
                                    enabled = enabled,
                                    onClick = { editTarget = TemplateEditTarget(template) },
                                    modifier = Modifier.testTag(templateEditButtonTag(template.id)),
                                ) {
                                    Text(stringResource(Res.string.template_edit))
                                }
                            }
                            if (deleteIcon != null) {
                                IconButton(
                                    enabled = enabled,
                                    onClick = { confirmDelete = template },
                                    modifier = Modifier.testTag(templateDeleteButtonTag(template.id)),
                                ) {
                                    Icon(
                                        painter = deleteIcon,
                                        contentDescription = stringResource(Res.string.tips_delete_template),
                                    )
                                }
                            } else {
                                TextButton(
                                    enabled = enabled,
                                    onClick = { confirmDelete = template },
                                    modifier = Modifier.testTag(templateDeleteButtonTag(template.id)),
                                ) {
                                    Text(stringResource(Res.string.template_delete))
                                }
                            }
                        }
                        HorizontalDivider()
                    }
                }
            }
        }
    }

    if (useLargeDialog) {
        EwmContentDialog(
            onDismissRequest = onDismiss,
            maxWidth = me.rosuh.easywatermark.ui.TEMPLATE_DIALOG_MAX_WIDTH_DP.dp,
            testTag = "templateListDialogHost",
            content = body,
        )
    } else {
        EwmModalBottomSheet(
            onDismissRequest = onDismiss,
            content = { body() },
        )
    }

    // add / edit content sheet
    editTarget?.let { target ->
        TemplateEditSheet(
            initialText = target.template?.content ?: newTemplateInitialText,
            editTitle = stringResource(Res.string.dialog_title_edit_watermark),
            confirmButton = stringResource(Res.string.tips_confirm_dialog),
            enabled = enabled,
            onConfirm = { text ->
                val t = target.template
                if (t != null) {
                    onUpdate(t.copy(content = text, lastModifiedDate = Clock.System.now()))
                } else {
                    onAdd(text)
                }
                editTarget = null
            },
            onDismiss = { editTarget = null }
        )
    }

    confirmUse?.let { template ->
        EwmConfirmDialog(
            onDismissRequest = { confirmUse = null },
            title = stringResource(Res.string.dialog_title_exist_confirm),
            text = stringResource(Res.string.tips_use_this_template),
            confirmLabel = stringResource(Res.string.tips_confirm_dialog),
            dismissLabel = stringResource(Res.string.tips_cancel_dialog),
            confirmEnabled = enabled,
            confirmTestTag = TEMPLATE_USE_CONFIRM_BUTTON_TAG,
            onConfirm = {
                onUse(template)
                confirmUse = null
                onDismiss()
            },
        )
    }

    confirmDelete?.let { template ->
        EwmConfirmDialog(
            onDismissRequest = { confirmDelete = null },
            title = stringResource(Res.string.dialog_title_exist_confirm),
            text = stringResource(Res.string.tips_delete_template),
            confirmLabel = stringResource(Res.string.tips_confirm_dialog),
            dismissLabel = stringResource(Res.string.tips_cancel_dialog),
            confirmEnabled = enabled,
            confirmTestTag = TEMPLATE_DELETE_CONFIRM_BUTTON_TAG,
            onConfirm = {
                onDelete(template)
                confirmDelete = null
            },
        )
    }
}

/** Distinguishes "add" (template == null) from "edit" (template != null). */
private data class TemplateEditTarget(val template: Template?)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TemplateEditSheet(
    initialText: String,
    editTitle: String,
    confirmButton: String,
    enabled: Boolean,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var draft by remember { mutableStateOf(initialText) }
    EwmModalBottomSheet(
        onDismissRequest = onDismiss,
        modifier = Modifier.testTag(TEMPLATE_EDIT_SHEET_TAG),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .navigationBarsPadding()
                .padding(bottom = 20.dp)
        ) {
            Text(
                text = editTitle,
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
                    .testTag(TEMPLATE_EDIT_FIELD_TAG),
                shape = RectangleShape,
            )
            Button(
                onClick = { onConfirm(draft) },
                enabled = enabled && draft.isNotBlank(),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 24.dp)
                    .testTag(TEMPLATE_EDIT_CONFIRM_BUTTON_TAG),
                shape = RectangleShape,
            ) {
                Text(text = confirmButton)
            }
        }
    }
}

/** Stable Compose testTag ids for the production template flow. */
const val TEMPLATE_LIST_SHEET_TAG = "templateListSheet"
const val TEMPLATE_ADD_BUTTON_TAG = "templateAddButton"
const val TEMPLATE_EDIT_SHEET_TAG = "templateEditSheet"
const val TEMPLATE_EDIT_FIELD_TAG = "templateEditField"
const val TEMPLATE_EDIT_CONFIRM_BUTTON_TAG = "templateEditConfirm"
const val TEMPLATE_USE_CONFIRM_BUTTON_TAG = "templateUseConfirm"
const val TEMPLATE_DELETE_CONFIRM_BUTTON_TAG = "templateDeleteConfirm"

fun templateRowTag(id: Int): String = "templateRow-$id"
fun templateEditButtonTag(id: Int): String = "templateEditButton-$id"
fun templateDeleteButtonTag(id: Int): String = "templateDeleteButton-$id"
