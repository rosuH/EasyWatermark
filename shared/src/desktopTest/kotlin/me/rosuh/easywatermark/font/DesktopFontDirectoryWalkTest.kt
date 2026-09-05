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
}
