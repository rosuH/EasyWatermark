package me.rosuh.easywatermark.font

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FontDirectorySafetyTest {
    @Test
    fun same_prefix_sibling_is_outside() {
        assertFalse(FontDirectorySafety.isCanonicalInside("/tmp/fonts", "/tmp/fonts-other/x.ttf"))
        assertFalse(FontDirectorySafety.isCanonicalInside("/tmp/fonts", "/tmp/fonts-other"))
    }

    @Test
    fun nested_file_is_inside() {
        assertTrue(FontDirectorySafety.isCanonicalInside("/tmp/fonts", "/tmp/fonts/a.ttf"))
        assertTrue(FontDirectorySafety.isCanonicalInside("/tmp/fonts", "/tmp/fonts"))
        assertTrue(FontDirectorySafety.isCanonicalInside("/tmp/fonts/", "/tmp/fonts/sub/a.ttf"))
    }
}
