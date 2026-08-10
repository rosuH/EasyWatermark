package me.rosuh.easywatermark.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.painter.Painter
import me.rosuh.easywatermark.data.model.entity.Template
import me.rosuh.easywatermark.ui.compose.TemplateListSheet

/**
 * Shared CMP host for the editor template sheet.
 * S-i18n-2: labels resolved inside [TemplateListSheet] via Res.
 */
@Composable
fun EditorTemplateSheetHost(
    templates: List<Template>,
    editIcon: Painter? = null,
    deleteIcon: Painter? = null,
    enabled: Boolean = true,
    newTemplateInitialText: String = "",
    /** ≥840 dual-pane: template list as centered dialog. */
    useLargeDialog: Boolean = false,
    onUse: (Template) -> Unit,
    onAdd: (String) -> Unit,
    onUpdate: (Template) -> Unit,
    onDelete: (Template) -> Unit,
    /**
     * Fired when the sheet opens/closes so hosts can defer [templates] collection until open
     * (Editor composition must not recompose solely because Room templates emitted).
     */
    onSheetVisibilityChange: (Boolean) -> Unit = {},
    content: @Composable (showTemplateSheet: () -> Unit) -> Unit,
) {
    var showTemplateSheet by remember { mutableStateOf(false) }

    content {
        if (!showTemplateSheet) {
            showTemplateSheet = true
            onSheetVisibilityChange(true)
        }
    }

    if (showTemplateSheet) {
        TemplateListSheet(
            templates = templates,
            editIcon = editIcon,
            deleteIcon = deleteIcon,
            enabled = enabled,
            newTemplateInitialText = newTemplateInitialText,
            useLargeDialog = useLargeDialog,
            onDismiss = {
                showTemplateSheet = false
                onSheetVisibilityChange(false)
            },
            onUse = onUse,
            onAdd = onAdd,
            onUpdate = onUpdate,
            onDelete = onDelete,
        )
    }
}
