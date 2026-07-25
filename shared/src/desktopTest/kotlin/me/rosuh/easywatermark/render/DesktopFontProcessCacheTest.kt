package me.rosuh.easywatermark.render

import kotlin.test.Test
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * H2: Desktop process-wide FontFamily + FontFamily.Resolver identity.
 */
class DesktopFontProcessCacheTest {

    @Test
    fun bundledLatinCjkFontFamily_isProcessWideSingletonPerLatinFirst() {
        val a = DesktopWatermarkTextRenderer.bundledLatinCjkFontFamily(latinFirst = true)
        val b = DesktopWatermarkTextRenderer.bundledLatinCjkFontFamily(latinFirst = true)
        assertSame(a, b, "latinFirst=true must return the same FontFamily instance")

        val c = DesktopWatermarkTextRenderer.bundledLatinCjkFontFamily(latinFirst = false)
        val d = DesktopWatermarkTextRenderer.bundledLatinCjkFontFamily(latinFirst = false)
        assertSame(c, d, "latinFirst=false must return the same FontFamily instance")
        // Distinct flags may share implementation but typically differ; only require identity per flag.
        assertTrue(a === b && c === d)
    }

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
