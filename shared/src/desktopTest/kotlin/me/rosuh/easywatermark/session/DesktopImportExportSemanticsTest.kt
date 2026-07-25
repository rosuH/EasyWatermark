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
import kotlin.test.assertTrue

/**
 * A3 **code-contract** tests for production seams (not OS Finder DnD):
 * - [DesktopSessionImport.commitImport] — selection without export (Open / Add more / Drop *handler* target)
 * - [DesktopSaveAsDestination.renderAndSaveExact] — Save As exact write
 * - [WatermarkSessionViewModel.exportAndAwait] — batch order + result writeback
 * - [desktopWindow_productionWiringGuard] — static fail-closed wiring of DesktopWindow callers
 *
 * OS multi-file Drop into the Compose window is a **manual/runtime gate**, not covered here.
 * Collision unique-naming remains owned by Port/SaveDecision tests (A2).
 */
class DesktopImportExportSemanticsTest {

    private class CountingExportPort : ExportPipelinePort {
        val calls = AtomicInteger(0)
        override suspend fun exportOne(
            imageInfo: ImageInfo,
            config: WaterMark,
            prefs: UserPreferences,
        ): ExportOutcome {
            calls.incrementAndGet()
            return ExportOutcome.failure(
                ExportFailure.Render(message = "import must not export"),
            )
        }
    }

    /** Records call order (uri + offset) and writes synthetic success without real render. */
    private class RecordingExportPort : ExportPipelinePort {
        data class Call(val uri: MediaRef, val offsetX: Float, val offsetY: Float)
        val received = CopyOnWriteArrayList<Call>()
        override suspend fun exportOne(
            imageInfo: ImageInfo,
            config: WaterMark,
            prefs: UserPreferences,
        ): ExportOutcome {
            received.add(Call(imageInfo.uri, imageInfo.offsetX, imageInfo.offsetY))
            val out = MediaRef("file://export/${received.size}/${imageInfo.uri.value}")
            imageInfo.width = 10
            imageInfo.height = 10
            return ExportOutcome.success(
                me.rosuh.easywatermark.data.model.ExportedMedia(
                    ref = out,
                    width = 10,
                    height = 10,
                    format = me.rosuh.easywatermark.data.model.ImageFormat.PNG,
                    byteCount = 1L,
                ),
            )
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
        // Import must not create stable export filenames under the workspace dir.
        assertTrue(dir.walkTopDown().none { it.isFile && it.name.startsWith("watermarked") })
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
        assertFalse(session.exportJobState.value.isSaving)
        assertEquals(0, session.exportJobState.value.totalCount)
        assertTrue(dir.walkTopDown().none { it.isFile && it.name.startsWith("watermarked") })
    }

    @Test
    fun saveAs_productionSeam_writesExactUserPath_notUniqueSibling() {
        val dir = tempDir("save-as-exact")
        File(dir, "watermarked.jpg").writeBytes(byteArrayOf(0x11, 0x22, 0x33))
        val chosen = File(dir, "user-chosen-name.jpg")
        val saved = DesktopSaveAsDestination.renderAndSaveExact(
            imageBytes = DesktopWatermarkComposer.sampleBackgroundPng(40, 30),
            request = me.rosuh.easywatermark.render.DesktopRenderRequest(
                config = WaterMark.default.copy(text = "SAVEAS"),
                prefs = UserPreferences.DEFAULT,
                offsetX = 0.5f,
                offsetY = 0.5f,
            ),
            userChosen = chosen,
        )
        assertEquals(chosen.absolutePath, saved.output.value)
        assertTrue(chosen.isFile && chosen.length() > 3)
        assertFalse(File(dir, "watermarked_1.jpg").exists(), "Save As must not unique-rename")
        assertContentEquals(byteArrayOf(0x11, 0x22, 0x33), File(dir, "watermarked.jpg").readBytes())
    }

    /**
     * Fail-closed **source wiring guard** (not an OS-window E2E): Preview/Save As must freeze
     * **same-item** path+offset via [freezeCurrentItemInput], and must not pair `lastImage.bytes`
     * with a separately resolved Session offset when a Session item exists.
     */
    @Test
    fun desktopWindow_productionWiringGuard_freezesSameItemSourceAndOffset() {
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

        assertTrue(
            "fun freezeCurrentItemInput" in text || "freezeCurrentItemInput():" in text,
            "must define freezeCurrentItemInput for same-item path+offset",
        )
        assertTrue(
            "FrozenItemInput" in text && "sourcePath" in text,
            "must freeze sourcePath with offsets together",
        )
        assertTrue("sourcePath" in text, "FrozenItemInput must carry sourcePath")

        // Save As body
        val saveStart = text.indexOf("fun saveAsExactPath")
        assertTrue(saveStart >= 0)
        val saveEnd = text.indexOf("Window(onCloseRequest", saveStart)
        val saveBody = text.substring(saveStart, if (saveEnd > saveStart) saveEnd else text.length)
        assertTrue("DesktopSaveAsDestination.renderAndSaveExact" in saveBody)
        assertTrue("DesktopRenderRequest" in saveBody)
        assertTrue("freezeCurrentItemInput" in saveBody, "Save As must freeze same-item input before IO")
        assertTrue("frozen.sourcePath" in saveBody, "Save As must read frozen path, not re-resolve curImageInfo")
        assertTrue("frozen.offsetX" in saveBody && "frozen.offsetY" in saveBody)
        // Fail-closed ordering: full DesktopRenderRequest (config+prefs+offset) before withContext(IO).
        val reqIdx = saveBody.indexOf("DesktopRenderRequest(")
        val ioIdx = saveBody.indexOf("withContext(Dispatchers.IO)")
        assertTrue(reqIdx >= 0, "Save As must construct DesktopRenderRequest")
        assertTrue(ioIdx >= 0, "Save As must use withContext(Dispatchers.IO) for file work")
        assertTrue(
            reqIdx < ioIdx,
            "Save As must build DesktopRenderRequest before withContext(Dispatchers.IO) " +
                "(reqIdx=$reqIdx ioIdx=$ioIdx) — config/prefs/offset are a pre-IO snapshot",
        )
        val wmFirstIdx = saveBody.indexOf("waterMark.first()")
        val prefsFirstIdx = saveBody.indexOf("userPreferences.first()")
        assertTrue(wmFirstIdx >= 0 && wmFirstIdx < ioIdx, "config first() must be before IO")
        assertTrue(prefsFirstIdx >= 0 && prefsFirstIdx < ioIdx, "prefs first() must be before IO")
        // Must not pair lastImage.bytes with independently re-read Session offset.
        assertFalse(
            Regex("""lastImage\?\.bytes[\s\S]{0,400}curImageInfo""").containsMatchIn(saveBody),
            "Save As must not pair lastImage.bytes with a re-resolved curImageInfo",
        )
        assertFalse("resolveUniqueOutputFile" in saveBody)
        assertFalse("runSaveFlow" in saveBody)

        // Preview freezes same-item path+offset
        val previewStart = text.indexOf("suspend fun refreshPreview")
        assertTrue(previewStart >= 0)
        val previewEnd = text.indexOf("LaunchedEffect(previewGeneration)", previewStart)
        val previewBody = text.substring(previewStart, if (previewEnd > previewStart) previewEnd else text.length)
        assertTrue("freezeCurrentItemInput" in previewBody)
        assertTrue("frozen.sourcePath" in previewBody)
        assertTrue("frozen.offsetX" in previewBody)
        assertTrue("runSaveFlow" in previewBody)
        assertFalse(
            Regex("""current\.bytes[\s\S]{0,300}currentItemOffsetSnapshot""").containsMatchIn(previewBody),
            "Preview must not use lastImage bytes with a separate offset snapshot helper",
        )
        assertFalse("currentItemOffsetSnapshot" in text, "offset-only helper must be removed")

        // onDrop body — import-only
        val dropStart = text.indexOf("override fun onDrop")
        assertTrue(dropStart >= 0)
        val dropEnd = text.indexOf("fun saveAsExactPath", dropStart)
        val dropBody = text.substring(dropStart, if (dropEnd > dropStart) dropEnd else text.length)
        assertTrue("importBatchLatest" in dropBody || "openImageFilesBatch" in dropBody)
        assertFalse("exportAndAwait" in dropBody)

        assertTrue("DesktopSessionImport.commitImport" in text)
        assertTrue("showOpenGallery = lastSavedFile != null" in text)
        assertTrue("desktop_ready_status" in text)
        assertTrue("desktop_drop_busy" in text)
        assertTrue("desktop_importing" in text)
        assertTrue(
            text.contains("offsetX = 0.5f") && text.contains("offsetY = 0.5f"),
            "no-session fixture path must pass explicit center offsets",
        )
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
        val selected = session.launchScreenUiStateFlow.value.selectedImageList
        assertEquals(2, selected.size)
        session.exportAndAwait(selected)
        assertEquals(
            listOf(selected[0].uri, selected[1].uri),
            port.received.map { it.uri },
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

    /**
     * Batch export must deliver each item's own offset in order — one item cannot leak its offset
     * to the next. Does not re-own the Spine paint matrix (still Port adapter-level).
     */
    @Test
    fun exportAndAwait_isolatesPerItemOffset_inCallOrder() = runBlocking {
        val dir = tempDir("session-batch-offset")
        val port = RecordingExportPort()
        val (session, _) = newSession(dir, port)
        val i1 = ImageInfo(MediaRef("/virtual/a.png"), offsetX = 0.17f, offsetY = 0.83f)
        val i2 = ImageInfo(MediaRef("/virtual/b.png"), offsetX = 0.83f, offsetY = 0.17f)
        session.dispatchAndAwait(AppIntent.EnterEditor(selected = listOf(i1, i2)))
        val selected = session.launchScreenUiStateFlow.value.selectedImageList
        assertEquals(2, selected.size)
        session.exportAndAwait(selected)
        assertEquals(2, port.received.size)
        assertEquals(selected[0].uri, port.received[0].uri)
        assertEquals(0.17f, port.received[0].offsetX)
        assertEquals(0.83f, port.received[0].offsetY)
        assertEquals(selected[1].uri, port.received[1].uri)
        assertEquals(0.83f, port.received[1].offsetX)
        assertEquals(0.17f, port.received[1].offsetY)
    }
}
