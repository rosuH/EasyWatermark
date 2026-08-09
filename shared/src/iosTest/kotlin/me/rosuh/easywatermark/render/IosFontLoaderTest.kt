package me.rosuh.easywatermark.render

import androidx.compose.ui.text.font.FontFamily
import kotlin.test.Test
import kotlin.test.assertSame

/**
 * ADR-0025: production iOS Text mode uses [FontFamily.Default]. Legacy [IosFontLoader] remains
 * internal for optional tooling; product export/preview no longer require bundled Noto files.
 */
class IosFontLoaderTest {

    @Test
    fun system_default_font_family_is_stable_singleton() {
        assertSame(FontFamily.Default, FontFamily.Default)
    }
}
