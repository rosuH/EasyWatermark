package me.rosuh.easywatermark.render

import platform.Foundation.NSBundle
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * S4d-20C: the **iOS font-loader contract proof** (`iosSimulatorArm64Test` / `iosArm64` link). Proves
 * [IosFontLoader] compiles + links on both iOS targets and documents its loud-failure contract.
 *
 * Proof level: **compile + native test-executable LINK** (the S4d-20B bar). The resource-IO RUN needs an
 * iOS runtime + a real `.app` bundle carrying the font files (none here; do not install — S4d-20C
 * constraint), so the error-path assertions below are RUNTIME-deferred to C5. They are written so they
 * pass on a runtime: a test executable's bundle does not contain the Noto fonts, so a load attempt throws.
 */
class IosFontLoaderTest {

    @Test
    fun loader_default_resource_names_are_present() {
        // Defaults mirror the desktop bundled faces (desktopMain/resources/fonts). Link + value check.
        assertTrue(IosFontLoader.DEFAULT_LATIN_NAME.isNotEmpty())
        assertEquals("ttf", IosFontLoader.DEFAULT_LATIN_TYPE)
        assertTrue(IosFontLoader.DEFAULT_CJK_NAME.isNotEmpty())
        assertEquals("otf", IosFontLoader.DEFAULT_CJK_TYPE)
    }

    @Test
    fun missing_resource_throws_with_useful_message() {
        // RUNTIME-deferred: on an iOS runtime the (test/app) bundle has no such resource → loud failure.
        val e = assertFailsWith<IllegalStateException> {
            IosFontLoader.loadFontBytes(
                name = "definitely-missing-font-xyz",
                type = "ttf",
                bundle = NSBundle.mainBundle,
            )
        }
        assertTrue(
            e.message?.contains("definitely-missing-font-xyz") == true,
            "error must name the missing resource; was: ${e.message}",
        )
    }

    @Test
    fun bundled_family_missing_face_throws() {
        // RUNTIME-deferred: convenience path surfaces the same loud failure when a face is absent.
        assertFailsWith<IllegalStateException> {
            IosFontLoader.bundledFontFamily(
                latinName = "definitely-missing-latin-xyz",
                cjkName = "definitely-missing-cjk-xyz",
                bundle = NSBundle.mainBundle,
            )
        }
    }
}
