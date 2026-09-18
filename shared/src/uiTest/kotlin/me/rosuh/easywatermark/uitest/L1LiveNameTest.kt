package me.rosuh.easywatermark.uitest

import kotlin.test.Test
import kotlin.test.assertEquals

class L1LiveNameTest {
    @Test
    fun safe_keeps_tag_tokens() {
        assertEquals("sharedComposeEditorScreen", L1Live.safe("sharedComposeEditorScreen"))
    }

    @Test
    fun safe_strips_path_bits() {
        assertEquals("a-b", L1Live.safe("a/b"))
        assertEquals("secret", L1Live.safe("../secret"))
    }
}
