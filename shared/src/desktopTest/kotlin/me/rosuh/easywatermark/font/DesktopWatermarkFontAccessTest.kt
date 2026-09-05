package me.rosuh.easywatermark.font

import androidx.compose.ui.graphics.asSkiaBitmap
import kotlinx.coroutines.runBlocking
import me.rosuh.easywatermark.data.model.WaterMark
import me.rosuh.easywatermark.data.model.WatermarkFontRef
import me.rosuh.easywatermark.data.model.WatermarkMode
import me.rosuh.easywatermark.render.CommonWatermarkPipeline
import me.rosuh.easywatermark.render.DesktopWatermarkTextRenderer
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class DesktopWatermarkFontAccessTest {

    @Test
    fun lists_and_resolves_non_default_system_font() = runBlocking {
        val access = DesktopWatermarkFontAccess(root = File("build/tmp-desktop-fonts-${System.nanoTime()}"))
        val system = access.listSystemFonts()
        assertTrue(system.isNotEmpty(), "Skia FontMgr should list at least one family")
        val entry = system.first { it.ref != WatermarkFontRef.Default }
        val resolved = access.resolve(entry.ref)
        assertIs<FontResolution.Success>(resolved)
        assertTrue(resolved.displayName.isNotBlank())
    }

    @Test
    fun default_and_system_fonts_paint_distinct_cells() = runBlocking {
        val access = DesktopWatermarkFontAccess(root = File("build/tmp-desktop-fonts-${System.nanoTime()}"))
        val system = access.listSystemFonts()
        val candidate = system.firstOrNull {
            val name = it.displayName.lowercase()
            name.contains("times") || name.contains("courier") || name.contains("menlo") ||
                name.contains("monaco") || name.contains("serif")
        } ?: system.first()
        val resolved = access.resolve(candidate.ref)
        assertIs<FontResolution.Success>(resolved)
        val env = DesktopWatermarkTextRenderer.textRasterEnv()
        val config = WaterMark.default.copy(text = "FontBridge", markMode = WatermarkMode.Text)
        val defaultCell = CommonWatermarkPipeline.composeCell(
            imageWidth = 400,
            config = config,
            env = env,
            fontFamily = androidx.compose.ui.text.font.FontFamily.Default,
        )
        val systemCell = CommonWatermarkPipeline.composeCell(
            imageWidth = 400,
            config = config,
            env = env,
            fontFamily = resolved.family,
        )
        val defaultBytes = defaultCell.asSkiaBitmap().readPixels()
        val systemBytes = systemCell.asSkiaBitmap().readPixels()
        val distinct = defaultCell.width != systemCell.width ||
            defaultCell.height != systemCell.height ||
            defaultBytes?.contentHashCode() != systemBytes?.contentHashCode()
        assertTrue(distinct, "Non-default system font should change raster vs FontFamily.Default")
        val out = File("build/font-bridge-evidence").apply { mkdirs() }
        File(out, "default-cell.png").writeBytes(DesktopWatermarkTextRenderer.encodePng(defaultCell))
        File(out, "system-cell.png").writeBytes(DesktopWatermarkTextRenderer.encodePng(systemCell))
        File(out, "notes.txt").writeText(
            "default=${defaultCell.width}x${defaultCell.height} system=${systemCell.width}x${systemCell.height} font=${candidate.displayName}\n",
        )
    }

    @Test
    fun import_macos_ttf_then_resolve_after_source_gone() = runBlocking {
        val source = File("/System/Library/Fonts/Supplemental/Courier New.ttf")
            .takeIf { it.isFile }
            ?: File("/Library/Fonts/Arial.ttf").takeIf { it.isFile }
            ?: return@runBlocking
        val root = File("build/tmp-desktop-import-${System.nanoTime()}").apply { mkdirs() }
        val access = DesktopWatermarkFontAccess(root = root)
        val copyDir = File(root, "src").apply { mkdirs() }
        val copied = File(copyDir, source.name)
        source.copyTo(copied, overwrite = true)
        val result = access.importDirectory(copyDir)
        assertEquals(1, result.added, result.toString())
        copyDir.deleteRecursively()
        val imported = access.listImportedFonts()
        assertEquals(1, imported.size)
        val resolved = access.resolve(imported.first().ref)
        assertIs<FontResolution.Success>(resolved)
    }
}
