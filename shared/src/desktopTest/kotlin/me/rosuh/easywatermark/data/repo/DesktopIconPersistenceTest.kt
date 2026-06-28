package me.rosuh.easywatermark.data.repo

import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * S4d-221: the Desktop sibling of the iOS icon-persistence tests — pins the observable invariants of
 * [DesktopIconPersistence.persistIcon] (the extracted S4d-219 copy-then-prune): bounded one helper-owned
 * file, different-extension prune, same-copy no-op, failed-source-read leaves the prior copy intact
 * (no data loss), and the blank-extension → `png` fallback.
 *
 * Deliberately does NOT assert the `ATOMIC_MOVE` option itself — atomicity is filesystem/environment
 * dependent; the asserted contract is no-data-loss + bounded output.
 */
class DesktopIconPersistenceTest {

    private val tmpRoot: File = Files.createTempDirectory("s4d221-icon").toFile()
    private fun iconsDir(): File = File(tmpRoot, "watermark_icons")
    private fun src(name: String, content: String): File =
        File(tmpRoot, name).apply { writeText(content) }
    private fun iconNames(): List<String> = iconsDir().listFiles()?.map { it.name }?.sorted() ?: emptyList()

    @AfterTest
    fun cleanup() {
        tmpRoot.deleteRecursively()
    }

    @Test
    fun copy_persists_one_bounded_file_with_content() {
        val copied = DesktopIconPersistence.persistIcon(src("logoA.png", "AAA"), iconsDir())
        assertEquals("icon.png", copied.name)
        assertEquals("AAA", copied.readText())
        assertEquals(listOf("icon.png"), iconNames()) // bounded to one helper-owned file
    }

    @Test
    fun different_extension_pick_prunes_old_and_stays_bounded() {
        DesktopIconPersistence.persistIcon(src("a.png", "AAA"), iconsDir())
        val copied = DesktopIconPersistence.persistIcon(src("b.jpg", "BBB"), iconsDir())
        assertEquals("icon.jpg", copied.name)
        assertEquals("BBB", copied.readText())
        assertEquals(listOf("icon.jpg"), iconNames()) // old icon.png pruned → still exactly one file
    }

    @Test
    fun repick_existing_copy_is_noop_and_keeps_content() {
        val copied = DesktopIconPersistence.persistIcon(src("c.png", "CCC"), iconsDir())
        val again = DesktopIconPersistence.persistIcon(copied, iconsDir()) // re-pick the helper-owned copy
        assertEquals(copied.canonicalFile, again.canonicalFile)
        assertTrue(again.exists())
        assertEquals("CCC", again.readText())
        assertEquals(listOf("icon.png"), iconNames())
    }

    @Test
    fun failed_source_read_throws_and_leaves_prior_copy_intact() {
        val good = DesktopIconPersistence.persistIcon(src("d.png", "DDD"), iconsDir())
        val before = good.readText()
        val missing = File(tmpRoot, "does-not-exist.png")
        val result = runCatching { DesktopIconPersistence.persistIcon(missing, iconsDir()) }
        assertTrue(result.isFailure, "a missing/unreadable source must throw")
        assertTrue(good.exists(), "prior copy must survive a failed read (no data loss)")
        assertEquals(before, good.readText())
    }

    @Test
    fun blank_extension_falls_back_to_png() {
        val copied = DesktopIconPersistence.persistIcon(src("iconfile", "EEE"), iconsDir()) // no dot → blank ext
        assertEquals("icon.png", copied.name)
        assertEquals("EEE", copied.readText())
    }
}
