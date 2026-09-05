package me.rosuh.easywatermark.font

import okio.FileSystem
import okio.Path.Companion.toOkioPath
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class WatermarkFontStoreTest {

    @Test
    fun publish_dedupes_same_bytes_and_keeps_distinct_content() {
        val dir = File("build/tmp-font-store-${System.nanoTime()}").apply { mkdirs() }
        try {
            val store = WatermarkFontStore(FileSystem.SYSTEM, dir.toOkioPath())
            val a = byteArrayOf(0, 1, 2, 3, 4)
            val b = byteArrayOf(9, 8, 7, 6, 5)
            val first = store.publishBytes("Alpha.ttf", a, accept)
            val dup = store.publishBytes("AlphaCopy.ttf", a, accept)
            val second = store.publishBytes("Beta.otf", b, accept)
            assertIs<FontPublishOutcome.Added>(first)
            assertIs<FontPublishOutcome.Duplicate>(dup)
            assertIs<FontPublishOutcome.Added>(second)
            assertEquals(2, store.listImported().size)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun recover_orhans_deletes_temp_dirs_only() {
        val dir = File("build/tmp-font-store-${System.nanoTime()}").apply { mkdirs() }
        try {
            val store = WatermarkFontStore(FileSystem.SYSTEM, dir.toOkioPath())
            File(dir, ".import-abc123").mkdirs()
            File(dir, "not-a-hash").mkdirs()
            store.recoverOrphans()
            assertTrue(!File(dir, ".import-abc123").exists())
            assertTrue(File(dir, "not-a-hash").exists())
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun importer_reports_unsupported_and_partial_success() {
        val dir = File("build/tmp-font-import-${System.nanoTime()}").apply { mkdirs() }
        try {
            val store = WatermarkFontStore(FileSystem.SYSTEM, dir.toOkioPath())
            val result = WatermarkFontImporter.importCandidates(
                store = store,
                candidates = listOf(
                    FontImportCandidate("skip.txt", 4) { okio.Buffer().write(byteArrayOf(1, 2, 3, 4)) },
                    FontImportCandidate("ok.ttf", 3) { okio.Buffer().write(byteArrayOf(1, 2, 3)) },
                ),
                validate = { _, _ -> ValidatedImportedFont("Ok", FontStyleCapability.NormalOnly) },
            )
            assertEquals(1, result.added)
            assertEquals(1, result.failed.size)
            assertEquals("skip.txt", result.failed.first().fileName)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun damaged_published_directory_is_not_deleted_on_reimport() {
        val dir = File("build/tmp-font-damaged-${System.nanoTime()}").apply { mkdirs() }
        try {
            val store = WatermarkFontStore(FileSystem.SYSTEM, dir.toOkioPath())
            val bytes = byteArrayOf(1, 2, 3, 4, 5)
            val added = store.publishBytes("Keep.ttf", bytes, accept)
            assertIs<FontPublishOutcome.Added>(added)
            val sha = (added.entry.ref as me.rosuh.easywatermark.data.model.WatermarkFontRef.Imported).sha256
            val published = File(dir, sha)
            File(published, "metadata.json").delete()
            val listed = store.listImported()
            assertEquals(1, listed.size)
            assertFalse(listed.first().available)

            val again = store.publishBytes("Keep.ttf", bytes, accept)
            assertIs<FontPublishOutcome.Failed>(again)
            assertTrue(published.exists())
            assertTrue(File(published, "font.ttf").isFile)
            assertEquals(1, store.listImported().size)
        } finally {
            dir.deleteRecursively()
        }
    }

    private val accept: (ByteArray, String) -> ValidatedImportedFont = { _, _ ->
        ValidatedImportedFont("Test", FontStyleCapability.NormalOnly)
    }
}
