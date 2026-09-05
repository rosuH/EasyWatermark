package me.rosuh.easywatermark.font

import okio.Buffer
import okio.Source
import okio.Timeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class WatermarkFontImporterBoundTest {

    @Test
    fun oversized_unknown_size_stream_is_rejected_without_full_allocation() {
        val source = CountingSource(totalBytes = 1024 * 1024)
        val result = WatermarkFontImporter.readBounded(source, maxBytes = 16 * 1024)
        assertIs<BoundedRead.TooLarge>(result)
        assertTrue(source.bytesRead <= 16 * 1024 + 8 * 1024, "read ${source.bytesRead}")
        assertTrue(source.bytesRead < 1024 * 1024)
    }

    @Test
    fun lying_size_does_not_block_valid_sibling() {
        val dir = okio.FileSystem.SYSTEM_TEMPORARY_DIRECTORY / "font-bound-${kotlin.random.Random.nextLong()}"
        val fs = okio.FileSystem.SYSTEM
        fs.createDirectories(dir)
        try {
            val store = WatermarkFontStore(fs, dir)
            val huge = CountingSource(totalBytes = 200_000)
            val result = WatermarkFontImporter.importCandidates(
                store = store,
                candidates = listOf(
                    FontImportCandidate("huge.ttf", sizeBytes = 12) { huge },
                    FontImportCandidate("ok.ttf", sizeBytes = 4) {
                        Buffer().write(byteArrayOf(1, 2, 3, 4))
                    },
                ),
                limits = FontImportLimits(maxFileBytes = 16_384, maxTotalBytes = 32_768),
                validate = { _, _ -> ValidatedImportedFont("Ok", FontStyleCapability.NormalOnly) },
            )
            assertEquals(1, result.added, result.toString())
            assertEquals(1, result.failed.size)
            assertEquals("huge.ttf", result.failed.first().fileName)
            assertTrue(huge.bytesRead < 200_000)
            assertEquals(1, store.listImported().size)
        } finally {
            fs.deleteRecursively(dir, mustExist = false)
        }
    }

    private class CountingSource(private val totalBytes: Long) : Source {
        var bytesRead: Long = 0
            private set

        override fun read(sink: Buffer, byteCount: Long): Long {
            if (bytesRead >= totalBytes) return -1L
            val remaining = totalBytes - bytesRead
            val n = minOf(byteCount, remaining, 1024)
            repeat(n.toInt()) { sink.writeByte(1) }
            bytesRead += n
            return n
        }

        override fun timeout(): Timeout = Timeout.NONE

        override fun close() = Unit
    }
}
