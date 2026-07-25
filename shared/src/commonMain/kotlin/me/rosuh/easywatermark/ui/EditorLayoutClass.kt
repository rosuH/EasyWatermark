package me.rosuh.easywatermark.ui

/**
 * I1 — thin shared layout class for the product editor / gallery shells.
 *
 * Hosts feed **window size in Dp** (not Android/iOS platform types). Domain stays pure Kotlin.
 *
 * Width-primary thresholds (height is not required for the four roadmap fixtures):
 * - Compact: width &lt; 600
 * - Medium: 600 ≤ width &lt; 1024
 * - Expanded: width ≥ 1024
 *
 * Fixtures (issue 13 §I1 / plan 48):
 * | Size | Class |
 * | 360×640 | Compact |
 * | 600×800 | Medium |
 * | 1024×768 | Expanded |
 * | 1440×900 | Expanded |
 */
enum class EditorLayoutClass {
    Compact,
    Medium,
    Expanded,
}

/** Width at which [EditorLayoutClass.Medium] begins (inclusive), in Dp. */
const val EDITOR_LAYOUT_MEDIUM_MIN_WIDTH_DP: Float = 600f

/** Width at which [EditorLayoutClass.Expanded] begins (inclusive), in Dp. */
const val EDITOR_LAYOUT_EXPANDED_MIN_WIDTH_DP: Float = 1024f

/**
 * Classify a host window from size in **Dp** (logical pixels, not raw px).
 * Non-finite or non-positive widths map to [EditorLayoutClass.Compact].
 */
fun editorLayoutClass(widthDp: Float, heightDp: Float = 0f): EditorLayoutClass {
    // heightDp reserved for future portrait/landscape nuances; classification is width-primary.
    @Suppress("UNUSED_PARAMETER")
    val ignoredHeight = heightDp
    if (!widthDp.isFinite() || widthDp <= 0f) return EditorLayoutClass.Compact
    return when {
        widthDp >= EDITOR_LAYOUT_EXPANDED_MIN_WIDTH_DP -> EditorLayoutClass.Expanded
        widthDp >= EDITOR_LAYOUT_MEDIUM_MIN_WIDTH_DP -> EditorLayoutClass.Medium
        else -> EditorLayoutClass.Compact
    }
}

/**
 * Gallery adaptive min cell size (Dp). At ~360dp width with ~1.5dp gutters yields ~4 columns;
 * wider windows gain more columns (not fixed-4-only).
 */
const val GALLERY_ADAPTIVE_MIN_CELL_DP: Float = 80f

/** Expanded editor supporting pane max width (Dp) for preview + controls row. */
const val EDITOR_EXPANDED_CONTROLS_PANE_MAX_DP: Float = 360f
