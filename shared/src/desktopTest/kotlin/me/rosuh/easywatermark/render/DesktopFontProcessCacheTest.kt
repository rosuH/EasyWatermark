package me.rosuh.easywatermark.render

import kotlin.test.Test
import kotlin.test.assertSame

/**
 * H2: Desktop process-wide FontFamily.Resolver identity (ADR-0025: no production bundled family cache).
 */
class DesktopFontProcessCacheTest {

    @Test
    fun textRasterEnv_sharesProcessWideResolver() {
        val e1 = DesktopWatermarkTextRenderer.textRasterEnv()
        val e2 = DesktopWatermarkTextRenderer.textRasterEnv()
        assertSame(
            e1.fontFamilyResolver,
            e2.fontFamilyResolver,
            "textRasterEnv must reuse process-wide FontFamily.Resolver",
        )
    }
}
