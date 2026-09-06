package me.rosuh.easywatermark.ui

import me.rosuh.easywatermark.data.model.WatermarkFontRef
import me.rosuh.easywatermark.font.FontEntry
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FontNameQueryTest {

    private val courier = FontEntry(WatermarkFontRef.System("sys", "courier"), "Courier New")
    private val georgia = FontEntry(WatermarkFontRef.System("sys", "georgia"), "Georgia")
    private val source = FontEntry(WatermarkFontRef.Imported("a".repeat(64)), "SourceSansPro-Bold")
    private val entries = listOf(courier, georgia, source)

    @Test
    fun case_insensitive_substring_match() {
        assertTrue(FontNameQuery.matches("Courier New", "courier"))
        assertTrue(FontNameQuery.matches("SourceSansPro-Bold", "SANS"))
        assertFalse(FontNameQuery.matches("Georgia", "courier"))
        assertEquals(listOf(courier), FontNameQuery.filter(entries, "CoUr"))
    }

    @Test
    fun trimmed_and_empty_query_restore_all() {
        assertEquals(entries, FontNameQuery.filter(entries, ""))
        assertEquals(entries, FontNameQuery.filter(entries, "   "))
        assertEquals(listOf(georgia), FontNameQuery.filter(entries, "  geo  "))
        assertFalse(FontNameQuery.isActive(" \t "))
        assertTrue(FontNameQuery.isActive(" geo "))
    }

    @Test
    fun no_match_returns_empty_without_dropping_identity() {
        val filtered = FontNameQuery.filter(entries, "zzz")
        assertTrue(filtered.isEmpty())
        assertEquals(3, entries.size)
        assertEquals("Courier New", courier.displayName)
    }
}
