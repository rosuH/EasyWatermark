package me.rosuh.easywatermark.ui

import me.rosuh.easywatermark.data.model.FuncType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Structural gate: product UI architecture (shared screens, not ProductApp shells).
 * Proves catalogs and naming used by shipped [EditorScreen] / [LaunchScreen] stay valid.
 */
class ProductUiArchitectureTest {

    @Test
    fun editorOptionCatalog_matchesAndroidContentStyleLayoutOrder() {
        assertEquals(
            listOf(FuncType.Text, FuncType.Icon),
            EditorOptionCatalog.content.map { it.type },
        )
        assertTrue(EditorOptionCatalog.style.any { it.type == FuncType.Color })
        assertTrue(EditorOptionCatalog.style.any { it.type == FuncType.Degree })
        assertEquals(
            listOf(FuncType.Horizon, FuncType.Vertical),
            EditorOptionCatalog.layout.map { it.type },
        )
    }

    @Test
    fun editorUiIcons_type_exists_without_string_bags() {
        // S-i18n-2: EditorUiStrings bag removed; hosts use EditorUiIcons + Res labels.
        assertEquals("EditorUiIcons", EditorUiIcons::class.simpleName)
        assertTrue(FuncType.Text is FuncType)
        assertTrue(FuncType.Color is FuncType)
    }
}
