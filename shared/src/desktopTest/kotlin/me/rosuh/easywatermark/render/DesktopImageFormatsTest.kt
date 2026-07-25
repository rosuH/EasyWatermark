package me.rosuh.easywatermark.render

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * J3 — WebP advertising matches ImageIO capability (no decoder dep).
 */
class DesktopImageFormatsTest {

    @Test
    fun baseExtensions_excludeWebp() {
        assertFalse("webp" in DesktopImageFormats.BASE_EXTENSIONS)
        assertTrue("png" in DesktopImageFormats.BASE_EXTENSIONS)
        assertTrue("jpg" in DesktopImageFormats.BASE_EXTENSIONS)
    }

    @Test
    fun chooserExtensions_webpOnlyIfDecodable() {
        val exts = DesktopImageFormats.chooserExtensions()
        val webpOk = DesktopImageFormats.isWebpDecodable()
        if (webpOk) {
            assertTrue("webp" in exts)
        } else {
            assertFalse("webp" in exts, "must not advertise webp without ImageIO reader")
        }
        // Base always present
        assertTrue(DesktopImageFormats.BASE_EXTENSIONS.all { it in exts })
    }

    @Test
    fun chooserExtensions_canForceNoWebp() {
        val exts = DesktopImageFormats.chooserExtensions(preferWebpWhenSupported = false)
        assertFalse("webp" in exts)
    }
}
