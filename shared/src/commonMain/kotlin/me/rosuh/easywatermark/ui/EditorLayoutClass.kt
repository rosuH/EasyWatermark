package me.rosuh.easywatermark.ui

/**
 * Hand-rolled editor layout class (ADR-0026 / S1).
 *
 * Hosts feed **window size in Dp** (not platform types). Width-primary:
 * - Compact: width &lt; 600 — vertical stack (B density)
 * - Medium: 600 ≤ width &lt; 840 — same stack as Compact (M1)
 * - Expanded: 840 ≤ width &lt; 1440 — supporting-pane A (preview+filmstrip | inspector) (D1)
 * - Wide: width ≥ 1440 — three-zone C (session images | canvas+filmstrip | inspector) (C-W1)
 *
 * Fixtures:
 * | Size | Class |
 * | 360×640 | Compact |
 * | 600×800 | Medium |
 * | 839×800 | Medium |
 * | 840×800 | Expanded |
 * | 1280×800 | Expanded |
 * | 1440×900 | Wide |
 */
enum class EditorLayoutClass {
    Compact,
    Medium,
    Expanded,
    Wide,
}

/** Width at which [EditorLayoutClass.Medium] begins (inclusive), in Dp. */
const val EDITOR_LAYOUT_MEDIUM_MIN_WIDTH_DP: Float = 600f

/** Width at which supporting-pane A ([EditorLayoutClass.Expanded]) begins (inclusive), in Dp. ADR-0026 D1. */
const val EDITOR_LAYOUT_EXPANDED_MIN_WIDTH_DP: Float = 840f

/** Width at which three-zone C ([EditorLayoutClass.Wide]) begins (inclusive), in Dp. ADR-0026 C-W1. */
const val EDITOR_LAYOUT_WIDE_MIN_WIDTH_DP: Float = 1440f

/**
 * Classify a host window from size in **Dp** (logical pixels, not raw px).
 * Non-finite or non-positive widths map to [EditorLayoutClass.Compact].
 */
fun editorLayoutClass(widthDp: Float, heightDp: Float = 0f): EditorLayoutClass {
    // heightDp reserved; classification is width-primary (K1: no FoldingFeature).
    @Suppress("UNUSED_PARAMETER")
    val ignoredHeight = heightDp
    if (!widthDp.isFinite() || widthDp <= 0f) return EditorLayoutClass.Compact
    return when {
        widthDp >= EDITOR_LAYOUT_WIDE_MIN_WIDTH_DP -> EditorLayoutClass.Wide
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

/** Supporting-pane / three-zone inspector max width (Dp). Fixed rail — A polish P1. */
const val EDITOR_EXPANDED_CONTROLS_PANE_MAX_DP: Float = 360f

/** Three-zone C left session-library max width (Dp). */
const val EDITOR_WIDE_SESSION_LIBRARY_MAX_DP: Float = 280f

/** Horizontal padding inside supporting panes (Dp) — A polish letterbox/clip fix. */
const val EDITOR_SUPPORTING_PANE_PADDING_DP: Float = 12f
