package me.rosuh.easywatermark.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.painter.Painter
import me.rosuh.easywatermark.data.model.entity.Template
import me.rosuh.easywatermark.ui.compose.TemplateListSheet
import me.rosuh.easywatermark.ui.compose.TemplateListSheetStrings

/**
 * Shared CMP host for the editor template sheet.
 *
 * Platform callers still provide localized strings, painters, and repository callbacks; this host
 * owns only the sheet visibility state and wires the shared template sheet into the screen content.
 */
@Composable
fun EditorTemplateSheetHost(
    templates: List<Template>,
    strings: TemplateListSheetStrings,
    editIcon: Painter? = null,
    deleteIcon: Painter? = null,
    enabled: Boolean = true,
    newTemplateInitialText: String = "",
    onUse: (Template) -> Unit,
    onAdd: (String) -> Unit,
    onUpdate: (Template) -> Unit,
    onDelete: (Template) -> Unit,
    content: @Composable (showTemplateSheet: () -> Unit) -> Unit,
) {
    var showTemplateSheet by remember { mutableStateOf(false) }

    content {
        showTemplateSheet = true
    }

    if (showTemplateSheet) {
        TemplateListSheet(
            templates = templates,
            strings = strings,
            editIcon = editIcon,
            deleteIcon = deleteIcon,
            enabled = enabled,
            newTemplateInitialText = newTemplateInitialText,
            onDismiss = { showTemplateSheet = false },
            onUse = onUse,
            onAdd = onAdd,
            onUpdate = onUpdate,
            onDelete = onDelete,
        )
    }
}
