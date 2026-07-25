package me.rosuh.easywatermark.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * I1 — pure layout class fixtures (issue 13 / plan 48).
 */
class EditorLayoutClassTest {

    @Test
    fun fixtures_fourRequiredBreakpoints() {
        assertEquals(EditorLayoutClass.Compact, editorLayoutClass(360f, 640f))
        assertEquals(EditorLayoutClass.Medium, editorLayoutClass(600f, 800f))
        assertEquals(EditorLayoutClass.Expanded, editorLayoutClass(1024f, 768f))
        assertEquals(EditorLayoutClass.Expanded, editorLayoutClass(1440f, 900f))
    }

    @Test
    fun thresholds_widthPrimary() {
        assertEquals(EditorLayoutClass.Compact, editorLayoutClass(599.9f, 2000f))
        assertEquals(EditorLayoutClass.Medium, editorLayoutClass(600f, 100f))
        assertEquals(EditorLayoutClass.Medium, editorLayoutClass(1023.9f, 100f))
        assertEquals(EditorLayoutClass.Expanded, editorLayoutClass(1024f, 100f))
    }

    @Test
    fun invalidWidth_isCompact() {
        assertEquals(EditorLayoutClass.Compact, editorLayoutClass(0f, 640f))
        assertEquals(EditorLayoutClass.Compact, editorLayoutClass(-1f, 640f))
        assertEquals(EditorLayoutClass.Compact, editorLayoutClass(Float.NaN, 640f))
    }

    @Test
    fun galleryMinCell_yieldsFourColumnsOnPhoneWidth() {
        // Approximate: columns ≈ floor(width / minCell) ignoring gutters.
        val phoneWidth = 360f
        val cols = (phoneWidth / GALLERY_ADAPTIVE_MIN_CELL_DP).toInt()
        assertEquals(4, cols, "min cell $GALLERY_ADAPTIVE_MIN_CELL_DP should allow ~4 cols at 360dp")
        val tabletWidth = 1024f
        val more = (tabletWidth / GALLERY_ADAPTIVE_MIN_CELL_DP).toInt()
        assertTrue(more > 4, "expanded width must yield more than 4 columns")
    }

    @Test
    fun expanded_usesSupportingPane() {
        assertTrue(editorLayoutClass(1024f, 768f) == EditorLayoutClass.Expanded)
        assertTrue(EDITOR_EXPANDED_CONTROLS_PANE_MAX_DP in 280f..480f)
    }
}
