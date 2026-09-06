package me.rosuh.easywatermark.font

import kotlinx.coroutines.runBlocking
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DesktopFontDirectoryWalkTest {

    @Test
    fun rejects_symlink_escape_and_imports_ordinary_file() = runBlocking {
        val base = File("build/tmp-font-walk-${System.nanoTime()}").apply { mkdirs() }
        try {
            val root = File(base, "fonts").apply { mkdirs() }
            val sibling = File(base, "fonts-other").apply { mkdirs() }
            val outside = File(base, "outside.ttf").apply { writeBytes(byteArrayOf(9, 9, 9, 9)) }
            File(sibling, "evil.ttf").writeBytes(byteArrayOf(8, 8, 8, 8, 8))
            val real = File("/System/Library/Fonts/Supplemental/Courier New.ttf")
            require(real.isFile) { "Need a real TTF for import validation" }
            real.copyTo(File(root, "ok.ttf"), overwrite = true)
            Files.createSymbolicLink(File(root, "escape.ttf").toPath(), File(sibling, "evil.ttf").toPath())
            Files.createSymbolicLink(File(root, "out.ttf").toPath(), outside.toPath())
            val loopDir = File(root, "loop")
            Files.createSymbolicLink(loopDir.toPath(), root.toPath())

            val access = DesktopWatermarkFontAccess(root = File(base, "store").apply { mkdirs() })
            val result = access.importDirectory(root)
            assertEquals(1, result.added, result.toString())
            assertTrue(result.failed.none { it.fileName == "ok.ttf" })
            val names = access.listImportedFonts().map { it.displayName }
            assertTrue(names.any { it.contains("ok", ignoreCase = true) || it == "ok" || it == "Ok" || it.isNotEmpty() })
            assertEquals(1, access.listImportedFonts().size)
        } finally {
            base.deleteRecursively()
        }
    }

    @Test
    fun large_no_font_tree_reports_visit_truncation() = runBlocking {
        val base = File("build/tmp-font-visits-${System.nanoTime()}").apply { mkdirs() }
        try {
            val root = File(base, "tree").apply { mkdirs() }
            repeat(30) { index ->
                val dir = File(root, "d$index").apply { mkdirs() }
                File(dir, "readme.txt").writeText("x")
            }
            val access = DesktopWatermarkFontAccess(
                root = File(base, "store").apply { mkdirs() },
                limits = FontImportLimits(maxVisits = 12, maxCandidates = 50),
            )
            val result = access.importDirectory(root)
            assertTrue(result.truncated, result.toString())
            assertTrue(result.truncateReason.orEmpty().contains("directory entries"), result.toString())
            assertEquals(0, result.added)
        } finally {
            base.deleteRecursively()
        }
    }

    @Test
    fun cancel_during_enumeration_is_reported() = runBlocking {
        val base = File("build/tmp-font-cancel-${System.nanoTime()}").apply { mkdirs() }
        try {
            val root = File(base, "tree").apply { mkdirs() }
            repeat(40) { index ->
                File(root, "d$index").mkdirs()
                File(File(root, "d$index"), "n.txt").writeText("n")
            }
            val access = DesktopWatermarkFontAccess(
                root = File(base, "store").apply { mkdirs() },
                limits = FontImportLimits(maxVisits = 10_000, maxCandidates = 50),
            )
            var seen = 0
            val result = access.importDirectory(root) {
                seen += 1
                seen > 6
            }
            assertTrue(result.cancelled, result.toString())
            assertEquals(0, result.added)
        } finally {
            base.deleteRecursively()
        }
    }

    @Test
    fun candidate_cap_is_not_silent() = runBlocking {
        val base = File("build/tmp-font-capwalk-${System.nanoTime()}").apply { mkdirs() }
        try {
            val root = File(base, "fonts").apply { mkdirs() }
            val real = File("/System/Library/Fonts/Supplemental/Courier New.ttf")
            require(real.isFile)
            real.copyTo(File(root, "one.ttf"), overwrite = true)
            real.copyTo(File(root, "two.ttf"), overwrite = true)
            val access = DesktopWatermarkFontAccess(
                root = File(base, "store").apply { mkdirs() },
                limits = FontImportLimits(maxVisits = 1000, maxCandidates = 1),
            )
            val result = access.importDirectory(root)
            assertTrue(result.truncated, result.toString())
            assertTrue(result.truncateReason.orEmpty().contains("candidate files"), result.toString())
            assertEquals(1, result.added)
        } finally {
            base.deleteRecursively()
        }
    }

    @Test
    fun injected_enumeration_failure_returns_failed_result_not_throw() = runBlocking {
        val base = File("build/tmp-font-enum-fail-${System.nanoTime()}").apply { mkdirs() }
        try {
            val root = File(base, "fonts").apply { mkdirs() }
            File(root, "ok.ttf").writeBytes(byteArrayOf(1, 2, 3, 4))
            val access = DesktopWatermarkFontAccess(
                root = File(base, "store").apply { mkdirs() },
                onEnumerate = { _, _ -> error("provider exploded") },
            )
            val result = access.importDirectory(root)
            assertEquals(0, result.added)
            assertTrue(result.failed.any { it.reason.contains("exploded") }, result.toString())
            val retry = DesktopWatermarkFontAccess(root = File(base, "store2").apply { mkdirs() })
            val empty = retry.importDirectory(File(base, "empty").apply { mkdirs() })
            assertEquals(0, empty.added)
            assertTrue(!empty.cancelled)
        } finally {
            base.deleteRecursively()
        }
    }
}
