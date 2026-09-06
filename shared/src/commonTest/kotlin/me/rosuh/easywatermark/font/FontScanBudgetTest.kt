package me.rosuh.easywatermark.font

import okio.Buffer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FontScanBudgetTest {

    @Test
    fun large_no_font_tree_truncates_with_reason() {
        val budget = FontScanBudget(FontImportLimits(maxVisits = 8, maxCandidates = 50))
        var stopped = false
        repeat(40) { index ->
            if (!budget.onVisit()) {
                stopped = true
                return@repeat
            }
            if (index % 7 == 0) {
                budget.offerCandidate(dummy("noise-$index.txt"))
            }
        }
        val snap = budget.snapshot()
        assertTrue(stopped)
        assertTrue(snap.truncated)
        assertTrue(snap.truncateReason.orEmpty().contains("directory entries"))
        assertEquals(9, snap.visited)
        assertFalse(snap.cancelled)
    }

    @Test
    fun cancel_during_enumeration_keeps_already_found_candidates() {
        var calls = 0
        val budget = FontScanBudget(
            FontImportLimits(maxVisits = 1000, maxCandidates = 50),
            cancelled = { calls += 1; calls > 2 },
        )
        budget.onVisit()
        budget.offerCandidate(dummy("one.ttf"))
        budget.onVisit()
        budget.offerCandidate(dummy("two.ttf"))
        assertFalse(budget.onVisit())
        val snap = budget.snapshot()
        assertTrue(snap.cancelled)
        assertFalse(snap.truncated)
        assertEquals(2, snap.candidates.size)
        assertEquals("one.ttf", snap.candidates[0].fileName)
    }

    @Test
    fun candidate_cap_is_reported_not_silent() {
        val budget = FontScanBudget(FontImportLimits(maxVisits = 1000, maxCandidates = 1))
        assertTrue(budget.onVisit())
        assertTrue(budget.offerCandidate(dummy("first.ttf")))
        assertTrue(budget.onVisit())
        assertFalse(budget.offerCandidate(dummy("second.ttf")))
        val snap = budget.snapshot()
        assertTrue(snap.truncated)
        assertTrue(snap.truncateReason.orEmpty().contains("candidate files"))
        assertEquals(1, snap.candidates.size)
    }

    @Test
    fun importEnumerated_merges_scan_truncation() {
        val dir = okio.FileSystem.SYSTEM_TEMPORARY_DIRECTORY / "font-enum-${kotlin.random.Random.nextLong()}"
        val fs = okio.FileSystem.SYSTEM
        fs.createDirectories(dir)
        try {
            val store = WatermarkFontStore(fs, dir)
            val enumeration = FontEnumerationResult(
                candidates = listOf(
                    FontImportCandidate("ok.ttf", 3) { Buffer().write(byteArrayOf(1, 2, 3)) },
                ),
                truncated = true,
                truncateReason = "Stopped after 8 directory entries",
            )
            val result = WatermarkFontImporter.importEnumerated(
                store = store,
                enumeration = enumeration,
                validate = { _, _ -> ValidatedImportedFont("Ok", FontStyleCapability.NormalOnly) },
            )
            assertEquals(1, result.added)
            assertTrue(result.truncated)
            assertEquals("Stopped after 8 directory entries", result.truncateReason)
        } finally {
            fs.deleteRecursively(dir, mustExist = false)
        }
    }

    private fun dummy(name: String): FontImportCandidate =
        FontImportCandidate(name, 1) { Buffer().write(byteArrayOf(1)) }
}
