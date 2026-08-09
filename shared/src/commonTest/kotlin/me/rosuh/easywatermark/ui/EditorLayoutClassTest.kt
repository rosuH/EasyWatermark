package me.rosuh.easywatermark.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * ADR-0026 layout class fixtures — Compact / Medium stack, Expanded@840, Wide@1440.
 */
class EditorLayoutClassTest {

    @Test
    fun fixtures_requiredBreakpoints() {
        assertEquals(EditorLayoutClass.Compact, editorLayoutClass(360f, 640f))
        assertEquals(EditorLayoutClass.Medium, editorLayoutClass(600f, 800f))
        assertEquals(EditorLayoutClass.Medium, editorLayoutClass(839f, 800f))
        assertEquals(EditorLayoutClass.Expanded, editorLayoutClass(840f, 800f))
        assertEquals(EditorLayoutClass.Expanded, editorLayoutClass(1024f, 768f))
        assertEquals(EditorLayoutClass.Expanded, editorLayoutClass(1439f, 900f))
        assertEquals(EditorLayoutClass.Wide, editorLayoutClass(1440f, 900f))
        assertEquals(EditorLayoutClass.Wide, editorLayoutClass(1600f, 900f))
    }

    @Test
    fun thresholds_widthPrimary_adr0026() {
        assertEquals(EditorLayoutClass.Compact, editorLayoutClass(599.9f, 2000f))
        assertEquals(EditorLayoutClass.Medium, editorLayoutClass(600f, 100f))
        assertEquals(EditorLayoutClass.Medium, editorLayoutClass(839.9f, 100f))
        assertEquals(EditorLayoutClass.Expanded, editorLayoutClass(840f, 100f))
        assertEquals(EditorLayoutClass.Expanded, editorLayoutClass(1439.9f, 100f))
        assertEquals(EditorLayoutClass.Wide, editorLayoutClass(1440f, 100f))
    }

    @Test
    fun invalidWidth_isCompact() {
        assertEquals(EditorLayoutClass.Compact, editorLayoutClass(0f, 640f))
        assertEquals(EditorLayoutClass.Compact, editorLayoutClass(-1f, 640f))
        assertEquals(EditorLayoutClass.Compact, editorLayoutClass(Float.NaN, 640f))
    }

    @Test
    fun galleryMinCell_yieldsFourColumnsOnPhoneWidth() {
        val phoneWidth = 360f
        val cols = (phoneWidth / GALLERY_ADAPTIVE_MIN_CELL_DP).toInt()
        assertEquals(4, cols, "min cell $GALLERY_ADAPTIVE_MIN_CELL_DP should allow ~4 cols at 360dp")
        val tabletWidth = 1024f
        val more = (tabletWidth / GALLERY_ADAPTIVE_MIN_CELL_DP).toInt()
        assertTrue(more > 4, "expanded width must yield more than 4 columns")
    }

    @Test
    fun expanded_usesSupportingPane_wide_usesThreeZone() {
        assertEquals(EditorLayoutClass.Expanded, editorLayoutClass(840f, 768f))
        assertEquals(EditorLayoutClass.Wide, editorLayoutClass(1440f, 900f))
        assertTrue(EDITOR_EXPANDED_CONTROLS_PANE_MAX_DP in 280f..480f)
        assertTrue(EDITOR_WIDE_SESSION_LIBRARY_MAX_DP in 200f..320f)
        assertEquals(840f, EDITOR_LAYOUT_EXPANDED_MIN_WIDTH_DP)
        assertEquals(1440f, EDITOR_LAYOUT_WIDE_MIN_WIDTH_DP)
    }

    @Test
    fun hardCut_belowWide_isExpanded() {
        // C-F1: shrink below 1440 → A (Expanded), not a half-rail.
        assertEquals(EditorLayoutClass.Expanded, editorLayoutClass(1439f, 900f))
        assertEquals(EditorLayoutClass.Wide, editorLayoutClass(1440f, 900f))
    }
}
