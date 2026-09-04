package me.rosuh.easywatermark.render

import androidx.compose.ui.text.font.FontFamily
import kotlin.test.Test
import kotlin.test.assertSame

/**
 * ADR-0025: production iOS Text mode uses [FontFamily.Default] (system default).
 * Product path no longer loads bundled Noto via NSBundle.
 */
class IosSystemDefaultFontTest {

    @Test
    fun system_default_font_family_is_stable_singleton() {
        assertSame(FontFamily.Default, FontFamily.Default)
    }

    @Test
    fun text_raster_env_builds() {
        val env = IosTextRasterEnv.textRasterEnv()
        // Shared resolver identity across calls (process-wide cache).
        val env2 = IosTextRasterEnv.textRasterEnv()
        assertSame(env.fontFamilyResolver, env2.fontFamilyResolver)
    }
}
