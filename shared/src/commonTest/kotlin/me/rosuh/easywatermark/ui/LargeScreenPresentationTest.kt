package me.rosuh.easywatermark.ui

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LargeScreenPresentationTest {

    @Test
    fun dual_pane_uses_large_dialog() {
        assertTrue(usesLargeScreenDialog(EditorLayoutClass.Expanded))
        assertTrue(usesLargeScreenDialog(EditorLayoutClass.Wide))
        assertFalse(usesLargeScreenDialog(EditorLayoutClass.Compact))
        assertFalse(usesLargeScreenDialog(EditorLayoutClass.Medium))
    }

    @Test
    fun form_inspector_matches_dual_pane() {
        assertTrue(usesFormInspectorPath(EditorLayoutClass.Expanded))
        assertFalse(usesFormInspectorPath(EditorLayoutClass.Compact))
    }
}
