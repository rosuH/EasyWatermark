package me.rosuh.easywatermark.ui.theme

import androidx.compose.ui.graphics.Color
import kotlin.test.Test
import kotlin.test.assertEquals

class EditorChromeColorTest {
    @Test
    fun resolve_nullScheme_isBrandOlive() {
        assertEquals(DesignEditorBg, EditorChromeColor.resolve(null))
        assertEquals(DesignBrand, EditorChromeColor.resolveAccent(null))
        assertEquals(DesignChipSelected, EditorChromeColor.resolveSelectedContainer(null))
    }

    @Test
    fun resolve_contentScheme_usesBackgroundAndPrimary() {
        val scheme = ContentEditorTheme.darkSchemeFromSeed(Color(0xFF1565C0))
        assertEquals(scheme.background, EditorChromeColor.resolve(scheme))
        assertEquals(scheme.primary, EditorChromeColor.resolveAccent(scheme))
        assertEquals(scheme.primaryContainer, EditorChromeColor.resolveSelectedContainer(scheme))
    }
}
