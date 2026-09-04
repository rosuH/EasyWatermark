package me.rosuh.easywatermark.session

import kotlinx.coroutines.runBlocking
import me.rosuh.easywatermark.data.datastore.createUserConfigDataStore
import me.rosuh.easywatermark.data.datastore.createWaterMarkDataStore
import me.rosuh.easywatermark.data.model.ExportedMedia
import me.rosuh.easywatermark.data.model.ImageFormat
import me.rosuh.easywatermark.data.model.ImageInfo
import me.rosuh.easywatermark.data.model.JobState
import me.rosuh.easywatermark.data.model.MediaRef
import me.rosuh.easywatermark.data.model.UserPreferences
import me.rosuh.easywatermark.data.model.WaterMark
import me.rosuh.easywatermark.data.model.WatermarkTileMode
import me.rosuh.easywatermark.data.repo.UserConfigRepository
import me.rosuh.easywatermark.data.repo.WaterMarkRepository
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * G3 gap fills only — production-seam cases not already covered by D2 C1–C4,
 * TypedExportSessionTest, DesktopExportPipelinePortTest, G1 atomic write, or G2 seed.
 *
 * Full matrix mapping lives in evidence/g3/matrix.md.
 */
class G3LifecycleFaultMatrixTest {

    private class TaxonomySequencePort(
        private val outcomes: List<ExportOutcome>,
    ) : ExportPipelinePort {
        override suspend fun exportOne(
            imageInfo: ImageInfo,
            config: WaterMark,
            prefs: UserPreferences,
        ): ExportOutcome = outcomes[received++]

        private var received = 0
    }

    private fun tempDir(name: String): File =
        File("build/g3-fault-$name-${System.nanoTime()}").apply {
            deleteRecursively()
            mkdirs()
        }

    private fun newSession(dir: File, port: ExportPipelinePort): WatermarkSessionViewModel {
        val waterRepo = WaterMarkRepository(
            dataStore = createWaterMarkDataStore(File(dir, "wm-store")),
            defaultTextProvider = { "EasyWatermark" },
            tileModeFromStorageId = { WatermarkTileMode.fromStorageId(it) },
            logError = {},
        )
        val userRepo = UserConfigRepository(createUserConfigDataStore(File(dir, "user-store")))
        return WatermarkSessionViewModel(
            waterMarkRepo = waterRepo,
            userConfigRepo = userRepo,
            exportPipeline = port,
        )
    }

    private fun publicExports(dir: File): List<File> =
        dir.listFiles()?.filter {
            it.isFile && (it.name.startsWith("watermarked") || it.extension in setOf("png", "jpg", "jpeg"))
        }.orEmpty()

    /**
     * Gap: corrupt/truncated source bytes on production Desktop port must fail closed
     * (typed failure) and leave **no** public export half-file under the output dir.
     */
    @Test
    fun g3_corruptTruncatedSource_noPublicHalfExport() = runBlocking {
        val dir = tempDir("corrupt-src")
        val source = File(dir, "truncated.png").apply {
            // Not a valid PNG payload — decode/compose path must fail.
            writeBytes(byteArrayOf(0x00, 0x01, 0x02, 0x03, 0xFF.toByte()))
        }
        val outDir = File(dir, "out").apply { mkdirs() }
        val port = DesktopExportPipelinePort(outputDirProvider = { outDir })
        val outcome = port.exportOne(
            ImageInfo(MediaRef(source.absolutePath)),
            WaterMark.default,
            UserPreferences.DEFAULT,
        )
        assertTrue(outcome.isFailure(), "corrupt source must be typed failure")
        val failure = (outcome as ExportOutcome.Failure).failure
        // Decode stack may surface as SourceDecode or Io depending on throw site — both fail-closed.
        assertTrue(
            failure is ExportFailure.SourceDecode || failure is ExportFailure.Io || failure is ExportFailure.Render,
            "expected SourceDecode/Io/Render, was ${failure::class.simpleName}: ${failure.message}",
        )
        assertTrue(
            publicExports(outDir).isEmpty(),
            "corrupt source must leave no public watermarked* half-file: ${publicExports(outDir)}",
        )
    }

    /**
     * Gap: Session batch maps Permission + Io distinctly; terminal state leaves no stuck
     * [JobState.Ing] and [ExportJobState.isSaving] is false (retry-ready without recreating config).
     */
    @Test
    fun g3_sessionBatch_permissionAndIo_mapCodes_terminalClean() = runBlocking {
        val dir = tempDir("perm-io")
        val port = TaxonomySequencePort(
            listOf(
                ExportOutcome.failure(ExportFailure.Permission(message = "denied")),
                ExportOutcome.failure(ExportFailure.Io(message = "disk full")),
                ExportOutcome.success(
                    ExportedMedia(
                        ref = MediaRef("file:///ok-g3.jpg"),
                        width = 8,
                        height = 8,
                        format = ImageFormat.JPEG,
                        byteCount = 50L,
                    ),
                ),
            ),
        )
        val session = newSession(dir, port)
        val batch = listOf(
            ImageInfo(MediaRef("/g3/a")),
            ImageInfo(MediaRef("/g3/b")),
            ImageInfo(MediaRef("/g3/c")),
        )
        session.dispatchAndAwait(AppIntent.EnterEditor(selected = batch))
        val selected = session.launchScreenUiStateFlow.value.selectedImageList
        session.exportAndAwait(selected)

        assertIs<JobState.Failure>(selected[0].jobState)
        assertEquals(ExportErrorCodes.PERMISSION, selected[0].result?.code)
        assertEquals("denied", selected[0].result?.message)

        assertIs<JobState.Failure>(selected[1].jobState)
        assertEquals(ExportErrorCodes.IO, selected[1].result?.code)
        assertEquals("disk full", selected[1].result?.message)

        assertIs<JobState.Success>(selected[2].jobState)
        assertEquals("file:///ok-g3.jpg", (selected[2].result?.data as MediaRef).value)

        val job = session.exportJobState.value
        assertFalse(job.isSaving, "isSaving must clear after terminal batch")
        assertTrue(job.isFinished)
        assertEquals(1, job.successCount)
        assertEquals(2, job.failureCount)
        assertTrue(selected.none { it.jobState is JobState.Ing }, "no stuck Ing")
        // Retry without recreating config: prior Success preserved; failures re-queued as Ready by startExport.
        session.exportAndAwait(selected)
        assertFalse(session.exportJobState.value.isSaving)
        assertTrue(selected.none { it.jobState is JobState.Ing })
    }
}
