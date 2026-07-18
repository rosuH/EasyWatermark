package me.rosuh.easywatermark.ui.compose

import androidx.compose.foundation.clickable
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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
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
    onDismiss: () -> Unit,
    onUse: (Template) -> Unit,
    onAdd: (String) -> Unit,
    onUpdate: (Template) -> Unit,
    onDelete: (Template) -> Unit,
) {
    var editTarget by remember { mutableStateOf<TemplateEditTarget?>(null) }
    var confirmUse by remember { mutableStateOf<Template?>(null) }
    var confirmDelete by remember { mutableStateOf<Template?>(null) }

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
                                    .clickable(enabled = enabled) { confirmUse = template }
                                    .padding(vertical = 16.dp)
                            )
                            if (editIcon != null) {
                                IconButton(
                                    enabled = enabled,
                                    onClick = { editTarget = TemplateEditTarget(template) },
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
                                ) {
                                    Text(stringResource(Res.string.template_edit))
                                }
                            }
                            if (deleteIcon != null) {
                                IconButton(
                                    enabled = enabled,
                                    onClick = { confirmDelete = template },
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
        AlertDialog(
            onDismissRequest = { confirmUse = null },
            title = { Text(stringResource(Res.string.dialog_title_exist_confirm)) },
            text = { Text(stringResource(Res.string.tips_use_this_template)) },
            confirmButton = {
                TextButton(
                    enabled = enabled,
                    onClick = {
                        onUse(template)
                        confirmUse = null
                        onDismiss()
                    },
                ) { Text(stringResource(Res.string.tips_confirm_dialog)) }
            },
            dismissButton = {
                TextButton(onClick = { confirmUse = null }) {
                    Text(stringResource(Res.string.tips_cancel_dialog))
                }
            }
        )
    }

    confirmDelete?.let { template ->
        AlertDialog(
            onDismissRequest = { confirmDelete = null },
            title = { Text(stringResource(Res.string.dialog_title_exist_confirm)) },
            text = { Text(stringResource(Res.string.tips_delete_template)) },
            confirmButton = {
                TextButton(
                    enabled = enabled,
                    onClick = {
                        onDelete(template)
                        confirmDelete = null
                    },
                ) { Text(stringResource(Res.string.tips_confirm_dialog)) }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = null }) {
                    Text(stringResource(Res.string.tips_cancel_dialog))
                }
            }
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
                    .wrapContentHeight(),
                shape = RectangleShape,
            )
            Button(
                onClick = { onConfirm(draft) },
                enabled = enabled && draft.isNotBlank(),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 24.dp),
                shape = RectangleShape,
            ) {
                Text(text = confirmButton)
            }
        }
    }
}
