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
        session.completeImport(generation, FontImportResult(added = 1, duplicates = 0, failed = emptyList()))
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
        session.completeImport(second, FontImportResult(added = 0, duplicates = 0, failed = emptyList()))
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

    @Test
    fun visible_rows_beyond_prefix_load_samples_and_tab_return_keeps_names() = runBlocking {
        val access = FakeFontAccess(systemCount = 25)
        val session = FontPanelSession(
            access = access,
            scope = this,
            applySelection = { _, _, _ -> true },
        )
        session.onOpen()
        var spins = 0
        while (session.state.systemFonts.size < 25 && spins++ < 200) {
            kotlinx.coroutines.yield()
        }
        assertEquals(25, session.state.systemFonts.size)
        val prefix = session.visibleEntries().take(FontPanelSession.SAMPLE_CACHE_MAX)
        session.ensureSamples(prefix)
        spins = 0
        while (session.state.sampleFamilies.size < FontPanelSession.SAMPLE_CACHE_MAX && spins++ < 200) {
            kotlinx.coroutines.yield()
        }
        val entry24 = session.state.systemFonts[24]
        val key24 = entry24.ref.fingerprint()
        assertFalse(session.state.sampleFamilies.containsKey(key24))

        session.onEvent(FontPanelEvent.VisibleEntries(session.state.systemFonts.drop(20)))
        spins = 0
        while (!session.state.sampleFamilies.containsKey(key24) && spins++ < 200) {
            kotlinx.coroutines.yield()
        }
        assertTrue(session.state.sampleFamilies.containsKey(key24), session.state.sampleFamilies.keys.toString())
        assertTrue(session.state.sampleFamilies.size <= FontPanelSession.SAMPLE_CACHE_MAX)
        assertEquals("Font 25", entry24.displayName)

        access.imported += FontEntry(
            ref = WatermarkFontRef.Imported("b".repeat(64)),
            displayName = "Imported One",
        )
        session.onEvent(FontPanelEvent.SourceTab(me.rosuh.easywatermark.font.FontSourceTab.Imported))
        spins = 0
        while (
            session.state.sampleFamilies.containsKey(
                WatermarkFontRef.Imported("b".repeat(64)).fingerprint(),
            ).not() && spins++ < 200
        ) {
            kotlinx.coroutines.yield()
        }
        session.onEvent(FontPanelEvent.SourceTab(me.rosuh.easywatermark.font.FontSourceTab.System))
        session.onEvent(FontPanelEvent.VisibleEntries(listOf(entry24)))
        spins = 0
        while (!session.state.sampleFamilies.containsKey(key24) && spins++ < 200) {
            kotlinx.coroutines.yield()
        }
        assertTrue(session.state.sampleFamilies.containsKey(key24))
        Unit
    }

    @Test
    fun cancel_after_partial_publish_refreshes_open_panel() = runBlocking {
        val access = FakeFontAccess()
        val session = FontPanelSession(
            access = access,
            scope = this,
            applySelection = { _, _, _ -> true },
        )
        session.beginImport()
        access.imported += FontEntry(
            ref = WatermarkFontRef.Imported("a".repeat(64)),
            displayName = "Published A",
        )
        session.cancelImport()
        var spins = 0
        while (
            session.state.importedFonts.none { it.displayName == "Published A" } &&
            spins++ < 200
        ) {
            kotlinx.coroutines.yield()
        }
        assertTrue(session.state.importedFonts.any { it.displayName == "Published A" })
        assertIs<FontImportProgress.Idle>(session.state.importProgress)
        assertEquals(WatermarkFontRef.Default, session.state.selectedRef)
        Unit
    }

    @Test
    fun cancel_then_new_import_does_not_clobber_running_progress() = runBlocking {
        val access = FakeFontAccess()
        val gate = kotlinx.coroutines.CompletableDeferred<Unit>()
        access.listGate = gate
        val session = FontPanelSession(
            access = access,
            scope = this,
            applySelection = { _, _, _ -> true },
        )
        val first = session.beginImport()
        access.imported += FontEntry(
            ref = WatermarkFontRef.Imported("a".repeat(64)),
            displayName = "Published A",
        )
        session.cancelImport()
        val second = session.beginImport()
        assertIs<FontImportProgress.Running>(session.state.importProgress)
        gate.complete(Unit)
        repeat(30) { kotlinx.coroutines.yield() }
        assertIs<FontImportProgress.Running>(session.state.importProgress)
        assertTrue(session.importStillCurrent(second))
        assertFalse(session.importStillCurrent(first))
        access.listGate = null
        session.completeImport(
            first,
            FontImportResult(added = 1, duplicates = 0, failed = emptyList()),
        )
        assertIs<FontImportProgress.Running>(session.state.importProgress)
        session.completeImport(
            second,
            FontImportResult(added = 0, duplicates = 0, failed = emptyList()),
        )
        assertIs<FontImportProgress.Done>(session.state.importProgress)
        assertTrue(session.state.importedFonts.any { it.displayName == "Published A" })
        Unit
    }

    @Test
    fun enumeration_exception_clears_running_and_next_import_works() = runBlocking {
        val session = FontPanelSession(
            access = FakeFontAccess(),
            scope = this,
            applySelection = { _, _, _ -> true },
        )
        val generation = session.beginImport()
        assertIs<FontImportProgress.Running>(session.state.importProgress)
        session.runImport(generation) { error("provider exploded") }
        val done = session.state.importProgress
        assertIs<FontImportProgress.Done>(done)
        assertTrue(done.result.failed.any { it.reason.contains("exploded") }, done.toString())
        val second = session.beginImport()
        assertIs<FontImportProgress.Running>(session.state.importProgress)
        session.completeImport(second, FontImportResult(added = 0, duplicates = 0, failed = emptyList()))
        assertIs<FontImportProgress.Done>(session.state.importProgress)
        Unit
    }

    private class FakeFontAccess(
        private val systemCount: Int = 2,
    ) : WatermarkFontAccess {
        val imported = mutableListOf<FontEntry>()
        var listGate: kotlinx.coroutines.CompletableDeferred<Unit>? = null

        override suspend fun listSystemFonts(): List<FontEntry> = (1..systemCount).map { index ->
            val key = if (index == 1) "alpha" else if (index == 2) "beta" else "f$index"
            FontEntry(WatermarkFontRef.System("test", key), "Font $index")
        }

        override suspend fun listImportedFonts(): List<FontEntry> {
            listGate?.await()
            return imported.toList()
        }

        override suspend fun resolve(ref: WatermarkFontRef): FontResolution = when (ref) {
            WatermarkFontRef.Default -> FontResolution.Success(
                FontFamily.Default,
                FontStyleCapability.All,
                "System default",
            )
            is WatermarkFontRef.System -> FontResolution.Success(
                family = when (ref.key) {
                    "alpha" -> FontFamily.SansSerif
                    "beta" -> FontFamily.Monospace
                    else -> FontFamily.Default
                },
                supportedStyles = FontStyleCapability.All,
                displayName = ref.key,
            )
            is WatermarkFontRef.Imported -> FontResolution.Success(
                FontFamily.Default,
                FontStyleCapability.All,
                "Imported",
            )
            else -> FontResolution.Failure("unavailable", ref)
        }

        override fun recoverOrphans() = Unit
    }
}
