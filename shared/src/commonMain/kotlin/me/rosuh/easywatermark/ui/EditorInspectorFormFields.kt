package me.rosuh.easywatermark.ui

import me.rosuh.easywatermark.data.model.FuncType
import me.rosuh.easywatermark.data.model.WatermarkMode

/**
 * Pure field-set helpers for Expanded/Wide form inspector (DEMO morphology).
 *
 * Content tab: Text|Icon segment chooses which body fields show — never both.
 * Style/Layout catalogs stay mode-agnostic (same controls apply to either mark).
 */
object EditorInspectorFormFields {

    /** Content body fields under the Text|Icon segment for [mode]. */
    fun contentFields(mode: WatermarkMode): List<FuncType> = when (mode) {
        WatermarkMode.Text -> listOf(FuncType.Text)
        WatermarkMode.Image -> listOf(FuncType.Icon)
    }

    /** Style form fields (full catalog order, no Content mode keys). */
    fun styleFields(): List<FuncType> = EditorOptionCatalog.style.map { it.type }

    /** Layout form fields. */
    fun layoutFields(): List<FuncType> = EditorOptionCatalog.layout.map { it.type }

    /** True when [type] is a slider that needs form left-label + right value. */
    fun isFormSlider(type: FuncType): Boolean = when (type) {
        FuncType.TextSize,
        FuncType.Alpha,
        FuncType.Degree,
        FuncType.Horizon,
        FuncType.Vertical,
        -> true
        else -> false
    }
}
