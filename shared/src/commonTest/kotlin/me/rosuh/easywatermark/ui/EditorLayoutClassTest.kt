package me.rosuh.easywatermark.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * ADR-0026 layout class fixtures — Compact / Medium stack, Expanded@800 dual-pane
 * (amended 2026-08-16 from 840 so 11" iPads reach dual-pane in portrait),
 * Wide@1440 classified but same dual-pane chrome (no left session rail).
 */
class EditorLayoutClassTest {

    @Test
    fun fixtures_requiredBreakpoints() {
        assertEquals(EditorLayoutClass.Compact, editorLayoutClass(360f, 640f))
        assertEquals(EditorLayoutClass.Medium, editorLayoutClass(600f, 800f))
        assertEquals(EditorLayoutClass.Medium, editorLayoutClass(799f, 800f))
        assertEquals(EditorLayoutClass.Expanded, editorLayoutClass(800f, 800f))
        assertEquals(EditorLayoutClass.Expanded, editorLayoutClass(1024f, 768f))
        assertEquals(EditorLayoutClass.Expanded, editorLayoutClass(1439f, 900f))
        assertEquals(EditorLayoutClass.Wide, editorLayoutClass(1440f, 900f))
        assertEquals(EditorLayoutClass.Wide, editorLayoutClass(1600f, 900f))
    }

    @Test
    fun thresholds_widthPrimary_adr0026() {
        assertEquals(EditorLayoutClass.Compact, editorLayoutClass(599.9f, 2000f))
        assertEquals(EditorLayoutClass.Medium, editorLayoutClass(600f, 100f))
        assertEquals(EditorLayoutClass.Medium, editorLayoutClass(799.9f, 100f))
        assertEquals(EditorLayoutClass.Expanded, editorLayoutClass(800f, 100f))
        assertEquals(EditorLayoutClass.Expanded, editorLayoutClass(1439.9f, 100f))
        assertEquals(EditorLayoutClass.Wide, editorLayoutClass(1440f, 100f))
    }

    /**
     * iPad portrait logical widths (points == Dp on iOS). Guards the ADR-0026 amendment:
     * every iPad except mini must reach dual-pane without relying on landscape.
     */
    @Test
    fun ipadPortraitWidths_reachDualPane() {
        assertEquals(EditorLayoutClass.Expanded, editorLayoutClass(820f, 1180f), "iPad Air 11\"")
        assertEquals(EditorLayoutClass.Expanded, editorLayoutClass(834f, 1210f), "iPad Pro 11\"")
        assertEquals(EditorLayoutClass.Expanded, editorLayoutClass(1024f, 1366f), "iPad Air 13\"")
        assertEquals(EditorLayoutClass.Expanded, editorLayoutClass(1032f, 1376f), "iPad Pro 13\"")
        // iPad mini stays on the stack — 744dp is too narrow for a 360dp inspector rail.
        assertEquals(EditorLayoutClass.Medium, editorLayoutClass(744f, 1133f), "iPad mini")
    }

    /** At the new floor the preview pane must still be wider than the phones Compact serves. */
    @Test
    fun expandedFloor_leavesUsablePreviewPane() {
        val previewPaneDp = EDITOR_LAYOUT_EXPANDED_MIN_WIDTH_DP -
            EDITOR_EXPANDED_CONTROLS_PANE_MAX_DP -
            (EDITOR_SUPPORTING_PANE_PADDING_DP * 2f)
        assertTrue(
            previewPaneDp >= 400f,
            "preview pane at the Expanded floor is ${previewPaneDp}dp, narrower than a large phone",
        )
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
    fun expandedAndWide_useSupportingPaneTokens() {
        assertEquals(EditorLayoutClass.Expanded, editorLayoutClass(800f, 768f))
        assertEquals(EditorLayoutClass.Wide, editorLayoutClass(1440f, 900f))
        assertTrue(EDITOR_EXPANDED_CONTROLS_PANE_MAX_DP in 280f..480f)
        assertEquals(800f, EDITOR_LAYOUT_EXPANDED_MIN_WIDTH_DP)
        assertEquals(1440f, EDITOR_LAYOUT_WIDE_MIN_WIDTH_DP)
    }

    @Test
    fun wideBand_stillClassifiedAt1440_dualPaneChrome() {
        // Wide remains a width band; chrome matches Expanded (no three-zone left rail).
        assertEquals(EditorLayoutClass.Expanded, editorLayoutClass(1439f, 900f))
        assertEquals(EditorLayoutClass.Wide, editorLayoutClass(1440f, 900f))
    }
}
