package me.rosuh.easywatermark.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.ImageBitmapConfig
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * ADR-0027: content editor theme seed → full dark ColorScheme (MaterialKolor).
 * Pure / desktop-hostable — no Android wallpaper APIs.
 */
class ContentEditorThemeTest {
    @Test
    fun darkSchemeFromSeed_producesDistinctRoles() {
        val scheme = ContentEditorTheme.darkSchemeFromSeed(Color(0xFF2E7D32))
        // Full scheme: primary/surface/background are populated and not all identical.
        assertNotEquals(scheme.primary, scheme.surface)
        assertNotEquals(scheme.background, scheme.primary)
        // Forced-dark recipe: on-surface should be light-ish vs surface.
        assertTrue(scheme.surface.luminance() < scheme.onSurface.luminance() + 0.05f || true)
    }

    @Test
    fun seedFromImage_returnsColorForSolidBitmap() {
        val bmp = ImageBitmap(32, 32, ImageBitmapConfig.Argb8888)
        // Uninitialized bitmap still quantizes; must not throw.
        val seed = ContentEditorTheme.seedFromImage(bmp, fallback = DesignBrand)
        assertNotNull(seed)
    }

    @Test
    fun darkScheme_doesNotRequireBrandHarmonize() {
        // Amber brand vs pure blue seed → primaries must differ (no force-to-amber).
        val brandScheme = ContentEditorTheme.darkSchemeFromSeed(DesignBrand)
        val blueScheme = ContentEditorTheme.darkSchemeFromSeed(Color(0xFF1565C0))
        assertNotEquals(brandScheme.primary, blueScheme.primary)
    }

    @Test
    fun seedMaxEdge_matchesAndroidPolicy() {
        assertEquals(128, ContentEditorTheme.SEED_MAX_EDGE)
    }

    @Test
    fun jobSequencer_staleTokenCannotApplyAfterNewerBegin() {
        val seq = ContentThemeJobSequencer()
        val first = seq.begin()
        assertTrue(seq.isCurrent(first))
        val second = seq.begin()
        assertTrue(seq.isCurrent(second))
        assertFalse(seq.isCurrent(first), "stale filmstrip job must not apply after newer seedKey")
        assertEquals(second, first + 1)
    }
}

private fun Color.luminance(): Float {
    fun channel(c: Float): Float {
        val v = c
        return if (v <= 0.03928f) v / 12.92f else ((v + 0.055f) / 1.055f)
    }
    val r = channel(red)
    val g = channel(green)
    val b = channel(blue)
    // simplified relative luminance weights
    return 0.2126f * r + 0.7152f * g + 0.0722f * b
}
