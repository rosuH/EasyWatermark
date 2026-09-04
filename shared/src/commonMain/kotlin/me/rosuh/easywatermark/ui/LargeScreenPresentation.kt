package me.rosuh.easywatermark.ui

/**
 * Shared presentation helpers for ≥800dp surfaces (export / template / about measure).
 * Breakpoints stay in [editorLayoutClass] — do not re-open ADR-0026 here.
 */

/** True when dual-pane / Desktop-class width should prefer dialog over phone bottom sheet. */
fun usesLargeScreenDialog(layoutClass: EditorLayoutClass): Boolean =
    layoutClass == EditorLayoutClass.Expanded || layoutClass == EditorLayoutClass.Wide

/** True when dual-pane form inspector path is active. */
fun usesFormInspectorPath(layoutClass: EditorLayoutClass): Boolean =
    usesLargeScreenDialog(layoutClass)

/** About / OSS readable content max width (Dp). */
const val ABOUT_CONTENT_MAX_WIDTH_DP: Float = 840f

/** Centered export dialog preferred width (Dp). */
const val EXPORT_DIALOG_MAX_WIDTH_DP: Float = 720f

/** Template list dialog preferred width (Dp). */
const val TEMPLATE_DIALOG_MAX_WIDTH_DP: Float = 560f

/** Long-form body measure (~65ch at 14sp body). */
const val LONG_TEXT_MAX_WIDTH_DP: Float = 520f
