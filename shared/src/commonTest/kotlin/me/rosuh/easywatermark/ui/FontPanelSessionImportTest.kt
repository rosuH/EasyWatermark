package me.rosuh.easywatermark.ui

import androidx.compose.ui.text.font.FontFamily
import kotlinx.coroutines.runBlocking
import me.rosuh.easywatermark.data.model.WatermarkFontRef
import me.rosuh.easywatermark.font.FontEntry
import me.rosuh.easywatermark.font.FontImportProgress
import me.rosuh.easywatermark.font.FontImportResult
import me.rosuh.easywatermark.font.FontResolution
import me.rosuh.easywatermark.font.FontStyleCapability
import me.rosuh.easywatermark.font.WatermarkFontAccess
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class FontPanelSessionImportTest {

    @Test
    fun beginImport_allocates_one_generation_and_complete_does_not_autoselect() = runBlocking {
        val access = FakeFontAccess()
        val session = FontPanelSession(
            access = access,
            scope = this,
            applySelection = { _, _, _ -> true },
        )
        assertEquals(WatermarkFontRef.Default, session.state.selectedRef)
        val generation = session.beginImport()
        assertTrue(session.importStillCurrent(generation))
        assertIs<FontImportProgress.Running>(session.state.importProgress)

        access.imported += FontEntry(
            ref = WatermarkFontRef.Imported("a".repeat(64)),
            displayName = "Imported",
        )
        session.completeImport(FontImportResult(added = 1, duplicates = 0, failed = emptyList()))
        assertTrue(session.importStillCurrent(generation))
        assertIs<FontImportProgress.Done>(session.state.importProgress)
        assertEquals(1, session.state.importedFonts.size)
        assertEquals(WatermarkFontRef.Default, session.state.selectedRef)
        Unit
    }

    @Test
    fun cancel_then_fresh_import_uses_new_generation() = runBlocking {
        val session = FontPanelSession(
            access = FakeFontAccess(),
            scope = this,
            applySelection = { _, _, _ -> true },
        )
        val first = session.beginImport()
        session.cancelImport()
        assertFalse(session.importStillCurrent(first))
        assertIs<FontImportProgress.Idle>(session.state.importProgress)
        val second = session.beginImport()
        assertTrue(session.importStillCurrent(second))
        assertFalse(session.importStillCurrent(first))
        session.completeImport(FontImportResult(added = 0, duplicates = 0, failed = emptyList()))
        assertIs<FontImportProgress.Done>(session.state.importProgress)
        Unit
    }

    @Test
    fun onOpen_loads_distinct_sample_families() = runBlocking {
        val access = FakeFontAccess()
        val session = FontPanelSession(
            access = access,
            scope = this,
            applySelection = { _, _, _ -> true },
        )
        session.onOpen()
        var spins = 0
        while (session.state.sampleFamilies.size < 2 && spins++ < 200) {
            kotlinx.coroutines.yield()
        }
        val samples = session.state.sampleFamilies
        val a = WatermarkFontRef.System("test", "alpha").fingerprint()
        val b = WatermarkFontRef.System("test", "beta").fingerprint()
        assertTrue(samples.containsKey(a) && samples.containsKey(b), samples.keys.toString())
        assertTrue(samples[a] !== samples[b] || samples[a] != samples[b])
        Unit
    }

    private class FakeFontAccess : WatermarkFontAccess {
        val imported = mutableListOf<FontEntry>()

        override suspend fun listSystemFonts(): List<FontEntry> = listOf(
            FontEntry(WatermarkFontRef.System("test", "alpha"), "Alpha"),
            FontEntry(WatermarkFontRef.System("test", "beta"), "Beta"),
        )

        override suspend fun listImportedFonts(): List<FontEntry> = imported.toList()

        override suspend fun resolve(ref: WatermarkFontRef): FontResolution = when (ref) {
            WatermarkFontRef.Default -> FontResolution.Success(
                FontFamily.Default,
                FontStyleCapability.All,
                "System default",
            )
            is WatermarkFontRef.System -> FontResolution.Success(
                family = if (ref.key == "alpha") FontFamily.SansSerif else FontFamily.Monospace,
                supportedStyles = FontStyleCapability.All,
                displayName = ref.key,
            )
            else -> FontResolution.Failure("unavailable", ref)
        }

        override fun recoverOrphans() = Unit
    }
}
