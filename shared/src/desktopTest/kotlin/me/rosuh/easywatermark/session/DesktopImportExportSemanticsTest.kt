package me.rosuh.easywatermark.session

import kotlinx.coroutines.runBlocking
import me.rosuh.easywatermark.data.datastore.createUserConfigDataStore
import me.rosuh.easywatermark.data.datastore.createWaterMarkDataStore
import me.rosuh.easywatermark.data.model.ImageFormat
import me.rosuh.easywatermark.data.model.ImageInfo
import me.rosuh.easywatermark.data.model.MediaRef
import me.rosuh.easywatermark.data.model.Result
import me.rosuh.easywatermark.data.model.UserPreferences
import me.rosuh.easywatermark.data.model.WaterMark
import me.rosuh.easywatermark.data.model.WatermarkTileMode
import me.rosuh.easywatermark.data.repo.UserConfigRepository
import me.rosuh.easywatermark.data.repo.WaterMarkRepository
import me.rosuh.easywatermark.render.DesktopSaveDecision
import me.rosuh.easywatermark.render.DesktopWatermarkComposer
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * A3 behavior: import-only selection (no export), Save As exact target, lastSaved vs preview,
 * and batch unique-name order. Drives production seams in [DesktopSessionImport],
 * [DesktopSaveAsDestination], [DesktopLastSavedPolicy], [DesktopExportPipelinePort].
 */
class DesktopImportExportSemanticsTest {

    private class CountingExportPort : ExportPipelinePort {
        val calls = AtomicInteger(0)
        override suspend fun exportOne(
            imageInfo: ImageInfo,
            config: WaterMark,
            prefs: UserPreferences,
        ): Result<MediaRef> {
            calls.incrementAndGet()
            return Result.failure(null, code = "UNEXPECTED", message = "import must not export")
        }
    }

    private fun tempDir(name: String): File =
        File("build/desktop-a3-semantics-$name-${System.nanoTime()}").apply {
            deleteRecursively()
            mkdirs()
        }

    private fun pngFile(dir: File, name: String, w: Int = 32, h: Int = 24): File {
        val f = File(dir, name)
        f.writeBytes(DesktopWatermarkComposer.sampleBackgroundPng(width = w, height = h))
        return f
    }

    private fun newSession(
        dir: File,
        port: ExportPipelinePort = CountingExportPort(),
    ): Pair<WatermarkSessionViewModel, ExportPipelinePort> {
        val waterRepo = WaterMarkRepository(
            dataStore = createWaterMarkDataStore(File(dir, "wm-store")),
            defaultTextProvider = { "EasyWatermark" },
            tileModeFromStorageId = { WatermarkTileMode.fromStorageId(it) },
            logError = {},
        )
        val userRepo = UserConfigRepository(createUserConfigDataStore(File(dir, "user-store")))
        val session = WatermarkSessionViewModel(
            waterMarkRepo = waterRepo,
            userConfigRepo = userRepo,
            exportPipeline = port,
        )
        return session to port
    }

    @Test
    fun commitImport_updatesSessionSelection_withoutCallingExportPort() = runBlocking {
        val dir = tempDir("import-no-export")
        val (session, port) = newSession(dir)
        val a = pngFile(dir, "a.png")
        val b = pngFile(dir, "b.png")
        val selected = DesktopSessionImport.commitImport(
            session = session,
            files = listOf(a, b),
            existingSelection = emptyList(),
            append = false,
            waterMark = WaterMark.default,
        )
        assertEquals(2, selected.size)
        val launch = session.launchScreenUiStateFlow.value
        assertEquals(2, launch.selectedImageList.size)
        assertEquals(a.absolutePath, launch.selectedImageList[0].uri.value)
        assertEquals(b.absolutePath, launch.selectedImageList[1].uri.value)
        assertEquals(a.absolutePath, launch.curImageInfo?.uri?.value)
        assertEquals(0, (port as CountingExportPort).calls.get())
        assertFalse(session.exportJobState.value.isSaving)
        assertEquals(0, session.exportJobState.value.totalCount)
        // No stable watermarked.* output created by import.
        assertTrue(dir.listFiles()?.none { it.name.startsWith("watermarked") } != false)
    }

    @Test
    fun commitImport_append_mergesWithoutExport() = runBlocking {
        val dir = tempDir("import-append")
        val (session, port) = newSession(dir)
        val a = pngFile(dir, "a.png")
        val b = pngFile(dir, "b.png")
        val c = pngFile(dir, "c.png")
        DesktopSessionImport.commitImport(
            session, listOf(a, b), emptyList(), append = false, WaterMark.default,
        )
        val prior = session.launchScreenUiStateFlow.value.selectedImageList
        DesktopSessionImport.commitImport(
            session, listOf(b, c), prior, append = true, WaterMark.default,
        )
        val paths = session.launchScreenUiStateFlow.value.selectedImageList.map { it.uri.value }
        assertEquals(
            listOf(a.absolutePath, b.absolutePath, c.absolutePath),
            paths,
        )
        assertEquals(0, (port as CountingExportPort).calls.get())
    }

    @Test
    fun lastSavedPolicy_previewNeverTracks_realOutputDoes() {
        val preview = File("/tmp/ewm-preview/preview.img")
        assertFalse(DesktopLastSavedPolicy.mayTrackAsLastSaved(preview, preview))
        assertTrue(
            DesktopLastSavedPolicy.mayTrackAsLastSaved(
                File("/Pictures/watermarked.jpg"),
                preview,
            ),
        )
    }

    @Test
    fun saveAs_exactTarget_writesUserChosenPath_notUniqueSibling() {
        val dir = tempDir("save-as-exact")
        // Occupy the default unique base name so a mistaken unique policy would create _1.
        File(dir, "watermarked.jpg").writeBytes(byteArrayOf(0x11, 0x22, 0x33))
        val chosen = File(dir, "user-chosen-name.jpg")
        val bytes = DesktopWatermarkComposer.sampleBackgroundPng(40, 30)
        val saved = DesktopSaveAsDestination.renderAndSaveExact(
            imageBytes = bytes,
            config = WaterMark.default.copy(text = "SAVEAS"),
            prefs = UserPreferences.DEFAULT,
            userChosen = chosen,
        )
        assertEquals(chosen.absolutePath, saved.output.value)
        assertTrue(chosen.isFile && chosen.length() > 3)
        assertFalse(File(dir, "watermarked_1.jpg").exists(), "Save As must not unique-rename")
        // Occupied base remains untouched by Save As to a different name.
        assertContentEquals(byteArrayOf(0x11, 0x22, 0x33), File(dir, "watermarked.jpg").readBytes())
    }

    @Test
    fun saveAs_exactTarget_overwritesSamePath_doesNotCreateSuffix() {
        val dir = tempDir("save-as-overwrite")
        val chosen = File(dir, "watermarked.jpg")
        val sentinel = byteArrayOf(0xDE.toByte(), 0xAD.toByte(), 0xBE.toByte(), 0xEF.toByte())
        chosen.writeBytes(sentinel)
        val saved = DesktopSaveAsDestination.renderAndSaveExact(
            imageBytes = DesktopWatermarkComposer.sampleBackgroundPng(48, 32),
            config = WaterMark.default,
            prefs = UserPreferences.DEFAULT,
            userChosen = DesktopSaveAsDestination.exactTarget(chosen),
        )
        assertEquals(chosen.absolutePath, saved.output.value)
        assertNotEquals(sentinel.toList(), chosen.readBytes().toList())
        assertFalse(File(dir, "watermarked_1.jpg").exists())
    }

    @Test
    fun desktopWindow_saveAs_routesThroughExactTarget_notUniqueHelper() {
        val relative = "desktopApp/src/main/kotlin/me/rosuh/easywatermark/desktop/DesktopWindow.kt"
        val cwd = File(System.getProperty("user.dir")!!)
        val candidates = listOf(
            File(cwd, relative),
            File(cwd.parentFile, relative),
            File(cwd, "../$relative"),
        )
        val window = candidates.firstOrNull { it.isFile }
            ?: error("DesktopWindow.kt not found from user.dir=$cwd candidates=$candidates")
        val text = window.readText()
        assertTrue(
            "DesktopSaveAsDestination.exactTarget" in text,
            "Save As caller must use DesktopSaveAsDestination.exactTarget",
        )
        assertTrue("DesktopSessionImport.commitImport" in text)
        // Isolate saveAsExactPath body: must not call unique naming.
        val start = text.indexOf("fun saveAsExactPath")
        assertTrue(start >= 0)
        val end = text.indexOf("Window(onCloseRequest", start)
        val body = text.substring(start, if (end > start) end else text.length)
        assertFalse(
            "resolveUniqueOutputFile" in body,
            "Save As must not use resolveUniqueOutputFile",
        )
        assertTrue("DesktopSaveAsDestination.exactTarget" in body)
        assertTrue("showOpenGallery = lastSavedFile != null" in text)
    }

    @Test
    fun batchExport_preservesOrder_andCollisionSafeNames() = runBlocking {
        val dir = tempDir("batch-export")
        val s1 = pngFile(dir, "src1.png", 64, 48)
        val s2 = pngFile(dir, "src2.png", 64, 48)
        // Pre-occupy first unique name so second export gets _1.
        File(dir, "watermarked.jpg").writeBytes(byteArrayOf(1))
        val port = DesktopExportPipelinePort(outputDirProvider = { dir })
        val i1 = ImageInfo(MediaRef(s1.absolutePath))
        val i2 = ImageInfo(MediaRef(s2.absolutePath))
        val r1 = port.exportOne(i1, WaterMark.default, UserPreferences.DEFAULT)
        val r2 = port.exportOne(i2, WaterMark.default, UserPreferences.DEFAULT)
        assertTrue(r1.isSuccess() && r2.isSuccess())
        val n1 = File(r1.data!!.value).name
        val n2 = File(r2.data!!.value).name
        assertEquals("watermarked_1.jpg", n1)
        assertEquals("watermarked_2.jpg", n2)
        assertNotEquals(r1.data!!.value, r2.data!!.value)
        // Contrast: exact Save As to watermarked.jpg would overwrite the sentinel, not create _3.
        val uniqueProbe = DesktopSaveDecision.resolveUniqueOutputFile(dir, ImageFormat.JPEG)
        assertEquals("watermarked_3.jpg", uniqueProbe.name)
    }
}
