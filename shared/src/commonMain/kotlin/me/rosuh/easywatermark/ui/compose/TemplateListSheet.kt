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
import kotlin.time.Clock

/**
 * Shared (commonMain) replacement for the legacy text-template surface —
 * `TextContentTemplateListFragment` (the list) + `EditTemplateContentFragment` (add/edit).
 * View→Compose migration, ADR-0016. Moved to commonMain in S4d-239.
 *
 * The template CRUD already lives in [me.rosuh.easywatermark.ui.MainViewModel]
 * (`templateListFlow` + add/update/delete); this is a pure UI port over those callbacks.
 * "Use" applies the template content via the same `updateText` path the text editor uses,
 * so it doesn't depend on the legacy `UiState.UseTemplate` plumbing.
 *
 * S4d-238 resource strategy: all text is passed as [TemplateListSheetStrings] (the Android
 * caller resolves `stringResource` at the edge); both icons are passed as [Painter] parameters
 * (the Android caller resolves `painterResource` at the edge). This composable has no
 * `R.string`/`R.drawable`/`stringResource`/`painterResource` dependencies.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TemplateListSheet(
    templates: List<Template>,
    strings: TemplateListSheetStrings,
    editIcon: Painter,
    deleteIcon: Painter,
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
                    text = strings.title,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                TextButton(onClick = { editTarget = TemplateEditTarget(null) }) {
                    Text(text = strings.addButton)
                }
            }

            if (templates.isEmpty()) {
                Text(
                    text = strings.empty,
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
                                    .clickable { confirmUse = template }
                                    .padding(vertical = 16.dp)
                            )
                            IconButton(onClick = { editTarget = TemplateEditTarget(template) }) {
                                Icon(
                                    painter = editIcon,
                                    contentDescription = strings.editTitle,
                                )
                            }
                            IconButton(onClick = { confirmDelete = template }) {
                                Icon(
                                    painter = deleteIcon,
                                    contentDescription = strings.deleteConfirm,
                                )
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
            initialText = target.template?.content ?: "",
            editTitle = strings.editTitle,
            confirmButton = strings.confirm,
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
            title = { Text(strings.existConfirmTitle) },
            text = { Text(strings.useThisTemplate) },
            confirmButton = {
                TextButton(onClick = {
                    onUse(template)
                    confirmUse = null
                    onDismiss()
                }) { Text(strings.confirm) }
            },
            dismissButton = {
                TextButton(onClick = { confirmUse = null }) {
                    Text(strings.cancel)
                }
            }
        )
    }

    confirmDelete?.let { template ->
        AlertDialog(
            onDismissRequest = { confirmDelete = null },
            title = { Text(strings.existConfirmTitle) },
            text = { Text(strings.deleteConfirm) },
            confirmButton = {
                TextButton(onClick = {
                    onDelete(template)
                    confirmDelete = null
                }) { Text(strings.confirm) }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = null }) {
                    Text(strings.cancel)
                }
            }
        )
    }
}

/**
 * Resolved string values for [TemplateListSheet]. The Android caller constructs this at the
 * edge using `stringResource(R.string.*)`; Desktop/iOS pass hard-coded English strings.
 * See S4d-238 resource strategy.
 */
data class TemplateListSheetStrings(
    val title: String,
    val addButton: String,
    val empty: String,
    val editTitle: String,
    val deleteConfirm: String,
    val existConfirmTitle: String,
    val useThisTemplate: String,
    val confirm: String,
    val cancel: String,
)

/** Distinguishes "add" (template == null) from "edit" (template != null). */
private data class TemplateEditTarget(val template: Template?)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TemplateEditSheet(
    initialText: String,
    editTitle: String,
    confirmButton: String,
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
                modifier = Modifier
                    .fillMaxWidth()
                    .wrapContentHeight(),
                shape = RectangleShape,
            )
            Button(
                onClick = { onConfirm(draft) },
                enabled = draft.isNotBlank(),
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
