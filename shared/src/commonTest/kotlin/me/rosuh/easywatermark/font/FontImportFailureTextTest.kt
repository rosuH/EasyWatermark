package me.rosuh.easywatermark.font

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FontImportFailureTextTest {

    @Test
    fun content_tree_uri_is_omitted_from_visible_failure() {
        val uri = "content://com.android.externalstorage.documents/tree/primary%3AFonts"
        val failure = FontImportFailureText.fromProviderException("Could not query $uri")
        assertEquals(FontImportFailureText.GENERIC_SOURCE, failure.fileName)
        val line = FontImportFailureText.visibleLine(
            failure.fileName,
            failure.reason,
            localizedSource = "Import",
        )
        val lower = line.lowercase()
        assertFalse("content://" in lower, line)
        assertFalse("primary%3a" in lower, line)
        assertFalse(uri in line, line)
        assertTrue(line.startsWith("Import:"), line)
        assertTrue("Could not query" in line, line)
    }

    @Test
    fun raw_tree_uri_filename_is_replaced() {
        val uri = "content://com.android.providers.downloads.documents/tree/raw:"
        val line = FontImportFailureText.visibleLine(uri, "open failed", "Import")
        assertFalse("content://" in line.lowercase(), line)
        assertEquals("Import: open failed", line)
    }
}
