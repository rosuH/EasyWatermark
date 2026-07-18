package me.rosuh.easywatermark.session

import kotlinx.coroutines.runBlocking
import me.rosuh.easywatermark.data.datastore.createUserConfigDataStore
import me.rosuh.easywatermark.data.datastore.createWaterMarkDataStore
import me.rosuh.easywatermark.data.model.ImageInfo
import me.rosuh.easywatermark.data.model.JobState
import me.rosuh.easywatermark.data.model.MediaRef
import me.rosuh.easywatermark.data.model.Result
import me.rosuh.easywatermark.data.model.UserPreferences
import me.rosuh.easywatermark.data.model.WaterMark
import me.rosuh.easywatermark.data.model.WatermarkTileMode
import me.rosuh.easywatermark.data.repo.UserConfigRepository
import me.rosuh.easywatermark.data.repo.WaterMarkRepository
import me.rosuh.easywatermark.render.DesktopWatermarkComposer
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * A3 behavior tests for production seams:
 * - [DesktopSessionImport.commitImport] (Open/Add/Drop) — selection without export
 * - [DesktopSaveAsDestination.renderAndSaveExact] (Save As production write)
 * - [WatermarkSessionViewModel.exportAndAwait] order + result writeback (batch Export)
 *
 * Collision unique-naming remains owned by Port/SaveDecision tests (A2); not re-run as Port E2E here.
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

    /** Records call order and writes synthetic success without real render. */
    private class RecordingExportPort : ExportPipelinePort {
        val received = CopyOnWriteArrayList<MediaRef>()
        override suspend fun exportOne(
            imageInfo: ImageInfo,
            config: WaterMark,
            prefs: UserPreferences,
        ): Result<MediaRef> {
            received.add(imageInfo.uri)
            val out = MediaRef("file://export/${received.size}/${imageInfo.uri.value}")
            imageInfo.width = 10
            imageInfo.height = 10
            return Result.success(out)
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
        assertEquals(listOf(a.absolutePath, b.absolutePath, c.absolutePath), paths)
        assertEquals(0, (port as CountingExportPort).calls.get())
    }

    @Test
    fun saveAs_productionSeam_writesExactUserPath_notUniqueSibling() {
        val dir = tempDir("save-as-exact")
        File(dir, "watermarked.jpg").writeBytes(byteArrayOf(0x11, 0x22, 0x33))
        val chosen = File(dir, "user-chosen-name.jpg")
        val saved = DesktopSaveAsDestination.renderAndSaveExact(
            imageBytes = DesktopWatermarkComposer.sampleBackgroundPng(40, 30),
            config = WaterMark.default.copy(text = "SAVEAS"),
            prefs = UserPreferences.DEFAULT,
            userChosen = chosen,
        )
        assertEquals(chosen.absolutePath, saved.output.value)
        assertTrue(chosen.isFile && chosen.length() > 3)
        assertFalse(File(dir, "watermarked_1.jpg").exists(), "Save As must not unique-rename")
        assertContentEquals(byteArrayOf(0x11, 0x22, 0x33), File(dir, "watermarked.jpg").readBytes())
    }

    @Test
    fun saveAs_productionSeam_overwritesSamePath_doesNotCreateSuffix() {
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
    fun desktopWindow_saveAs_callsProductionExactWriteSeam() {
        val relative = "desktopApp/src/main/kotlin/me/rosuh/easywatermark/desktop/DesktopWindow.kt"
        val cwd = File(System.getProperty("user.dir")!!)
        val candidates = listOf(
            File(cwd, relative),
            File(cwd.parentFile, relative),
            File(cwd, "../$relative"),
        )
        val window = candidates.firstOrNull { it.isFile }
            ?: error("DesktopWindow.kt not found from user.dir=$cwd")
        val text = window.readText()
        val start = text.indexOf("fun saveAsExactPath")
        assertTrue(start >= 0)
        val end = text.indexOf("Window(onCloseRequest", start)
        val body = text.substring(start, if (end > start) end else text.length)
        assertTrue(
            "DesktopSaveAsDestination.renderAndSaveExact" in body,
            "production Save As must call renderAndSaveExact (tested write seam)",
        )
        assertFalse("resolveUniqueOutputFile" in body)
        assertFalse(
            "runSaveFlow" in body,
            "Save As must not bypass the exact-write seam via runSaveFlow",
        )
        assertTrue("DesktopSessionImport.commitImport" in text)
        assertTrue("showOpenGallery = lastSavedFile != null" in text)
        // Import status uses resources (not raw English literals for those keys' values alone).
        assertTrue("Res.string.desktop_importing" in text || "desktop_importing" in text)
    }

    @Test
    fun exportAndAwait_preservesOrder_andWritesResultsOnSessionItems() = runBlocking {
        val dir = tempDir("session-batch")
        val port = RecordingExportPort()
        val (session, _) = newSession(dir, port)
        val i1 = ImageInfo(MediaRef("/virtual/a.png"))
        val i2 = ImageInfo(MediaRef("/virtual/b.png"))
        val batch = listOf(i1, i2)
        session.dispatchAndAwait(AppIntent.EnterEditor(selected = batch))
        // Use the same list instance the host would pass after selection.
        val selected = session.launchScreenUiStateFlow.value.selectedImageList
        assertEquals(2, selected.size)
        session.exportAndAwait(selected)
        assertEquals(
            listOf(selected[0].uri, selected[1].uri),
            port.received.toList(),
            "export port must see Session batch order",
        )
        assertTrue(selected[0].jobState is JobState.Success)
        assertTrue(selected[1].jobState is JobState.Success)
        assertEquals(
            "file://export/1/${selected[0].uri.value}",
            (selected[0].result?.data as MediaRef).value,
        )
        assertEquals(
            "file://export/2/${selected[1].uri.value}",
            (selected[1].result?.data as MediaRef).value,
        )
        val job = session.exportJobState.value
        assertTrue(job.isFinished)
        assertFalse(job.isSaving)
        assertEquals(2, job.totalCount)
        assertEquals(2, job.completedCount)
    }
}
