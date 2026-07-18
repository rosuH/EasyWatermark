package me.rosuh.easywatermark.data.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * Platform-neutral contracts for [MediaRef]. Mirrors the test style of `WatermarkTileModeTest` —
 * Pure-Kotlin assertions that run on every `:shared` target (commonTest). */
class MediaRefTest {

    @Test
    fun empty_is_empty_string_and_isEmpty() {
        assertTrue(MediaRef.Empty.isEmpty())
        assertEquals("", MediaRef.Empty.value)
        assertEquals("", MediaRef.Empty.toString())
    }

    @Test
    fun parse_round_trips_non_empty_string() {
        val ref = MediaRef.parse("content://media/external/images/media/42")
        assertFalse(ref.isEmpty())
        assertEquals("content://media/external/images/media/42", ref.value)
        assertEquals("content://media/external/images/media/42", ref.toString())
    }

    @Test
    fun parse_empty_string_is_empty_sentinel_equivalent() {
        // The legacy default is `Uri.parse("")`; whatever an older install persisted ("" or a real
        // uri), parse() must wrap it verbatim and the empty case must read as empty.
        val parsed = MediaRef.parse("")
        assertTrue(parsed.isEmpty())
        assertEquals(MediaRef.Empty.value, parsed.value)
    }

    @Test
    fun value_equality_is_string_equality() {
        // value classes use underlying-field equality; pin it so the round-trip guarantee
        // (`MediaRef(s) == MediaRef(s)`) is enforced.
        assertEquals(MediaRef("abc"), MediaRef("abc"))
        assertNotEquals(MediaRef("abc"), MediaRef("abd"))
        assertEquals(MediaRef.Empty, MediaRef(""))
    }

    @Test
    fun content_uris_and_file_uris_are_opaque_strings() {
        // MediaRef does not parse scheme/path — it is identity-only by design.
        val content = MediaRef.parse("content://media/external/images/media/7")
        val file = MediaRef.parse("file:///tmp/icon.png")
        assertEquals("content://media/external/images/media/7", content.value)
        assertEquals("file:///tmp/icon.png", file.value)
        assertNotEquals(content, file)
    }
}
