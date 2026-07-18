package me.rosuh.easywatermark.session

import kotlinx.coroutines.flow.first
import me.rosuh.easywatermark.data.datastore.createUserConfigDataStore
import me.rosuh.easywatermark.data.datastore.createWaterMarkDataStore
import me.rosuh.easywatermark.data.model.ImageInfo
import me.rosuh.easywatermark.data.model.JobState
import me.rosuh.easywatermark.data.model.MediaRef
import me.rosuh.easywatermark.data.model.WatermarkTileMode
import me.rosuh.easywatermark.data.repo.IosUserConfigBridge
import me.rosuh.easywatermark.data.repo.IosWatermarkConfigBridge
import me.rosuh.easywatermark.data.repo.UserConfigRepository
import me.rosuh.easywatermark.data.repo.WaterMarkRepository
import me.rosuh.easywatermark.render.IosByteArrayInterop
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSUUID
import platform.Foundation.writeToFile
import kotlin.experimental.ExperimentalObjCName
import kotlin.native.ObjCName

private const val DEFAULT_WATERMARK_TEXT = "EasyWatermark 水印"

/**
 * Single-process iOS graph (ADR-0017 Phase 4): **one** watermark DataStore + user prefs store,
 * Shared by config bridges and [WatermarkSessionViewModel] (DataStore forbids dual stores per file). */
@OptIn(ExperimentalObjCName::class)
@ObjCName(name = "IosAppServices", exact = true)
class IosAppServices(
    val waterMarkRepo: WaterMarkRepository,
    val userConfigRepo: UserConfigRepository,
    val configBridge: IosWatermarkConfigBridge,
    val userConfigBridge: IosUserConfigBridge,
    val session: WatermarkSessionViewModel,
) {
    /**
 * Stage [imageBytes] to a temp file, enter editor, **and** export a watermarked preview.
 * Prefer [stagePickedImagesBytes] + async preview when filmstrip must appear immediately.
     */
    @Throws(Exception::class)
    suspend fun exportPickedImageBytes(imageBytes: ByteArray): String {
        stagePickedImagesBytes(listOf(imageBytes), append = false)
        return exportFocusedPreview()
    }

    /**
 * Fast path: write picked bytes to temp files and update session selection / EnterEditor.
 * Does **not** run the watermark export pipeline (that is the multi-second cost).
 * Filmstrip can bind to [WatermarkSessionViewModel.launchScreenUiStateFlow] immediately after.
 *
 * @return source path of the focused (last newly staged) image.
     */
    @Throws(Exception::class)
    suspend fun stagePickedImagesBytes(
        imageBytesList: List<ByteArray>,
        append: Boolean,
    ): String {
        require(imageBytesList.isNotEmpty()) { "stagePickedImagesBytes: empty list" }
        val staged = imageBytesList.map { bytes ->
            require(bytes.isNotEmpty()) { "stagePickedImagesBytes: empty image" }
            val srcPath = NSTemporaryDirectory() + "ewm_src_" + NSUUID().UUIDString
            val wrote = IosByteArrayInterop.toNSData(bytes).writeToFile(srcPath, atomically = true)
            check(wrote) { "stagePickedImagesBytes: failed to stage source bytes" }
            ImageInfo(MediaRef(srcPath))
        }
        val prevLaunch = session.launchScreenUiStateFlow.first()
        val existing = if (append) {
            prevLaunch.selectedImageList
        } else {
            emptyList()
        }
        // Preserve focus on append so add-more does not snap filmstrip back to index 0.
        val previousCur = if (append) prevLaunch.curImageInfo else null
        val selected = me.rosuh.easywatermark.ui.ProductShellNav.mergePickedSelection(
            existing = existing,
            newly = staged,
            append = append,
        )
        val wm = waterMarkRepo.waterMark.first()
        // EnterEditor defaults focus to selected.first(); restore prior focus when appending.
        session.dispatchAndAwait(
            AppIntent.EnterEditor(
                selected = selected,
                waterMark = wm,
            ),
        )
        if (
            previousCur != null &&
            selected.any { it.uri == previousCur.uri } &&
            selected.firstOrNull()?.uri != previousCur.uri
        ) {
            session.dispatchAndAwait(AppIntent.SelectCurrent(previousCur.uri))
        }
        return staged.last().uri.value
    }

    /**
 * Run watermark export for the current session focus image only. Returns output path.
     */
    @Throws(Exception::class)
    suspend fun exportFocusedPreview(): String {
        val launch = session.launchScreenUiStateFlow.first()
        val focus = launch.curImageInfo
            ?: launch.selectedImageList.firstOrNull()
            ?: error("exportFocusedPreview: no current image")
        // Export the instance from the live selection list (same object refs as session state).
        val live = launch.selectedImageList.firstOrNull { it.uri == focus.uri } ?: focus
        session.exportAndAwait(listOf(live))
        return when (val st = live.jobState) {
            is JobState.Success -> {
                val ref = live.result?.data as? MediaRef
                    ?: error("export success without MediaRef")
                ref.value
            }
            is JobState.Failure -> {
                error(st.result.message ?: st.result.code ?: "export failed")
            }
            else -> error("export incomplete")
        }
    }

    /**
 * Stage + export (legacy combined path). Prefer stage then [exportFocusedPreview] for UI latency.
     */
    @Throws(Exception::class)
    suspend fun exportPickedImagesBytes(
        imageBytesList: List<ByteArray>,
        append: Boolean,
    ): String {
        stagePickedImagesBytes(imageBytesList, append)
        return exportFocusedPreview()
    }
}

/**
 * Build the single iOS service graph. **Process-wide singleton** — DataStore forbids a second
 * Active instance per file; Swift must not create a parallel graph for the product host. */
fun defaultIosAppServices(): IosAppServices = IosAppServicesHolder.instance

private object IosAppServicesHolder {
    val instance: IosAppServices by lazy {
        val waterMarkRepo = WaterMarkRepository(
            dataStore = createWaterMarkDataStore(),
            defaultTextProvider = { DEFAULT_WATERMARK_TEXT },
            tileModeFromStorageId = { WatermarkTileMode.fromStorageId(it) },
            logError = { message -> println("IosAppServices/WaterMarkRepository: $message") },
        )
        val userConfigRepo = UserConfigRepository(createUserConfigDataStore())
        val session = WatermarkSessionViewModel(
            waterMarkRepo = waterMarkRepo,
            userConfigRepo = userConfigRepo,
            exportPipeline = IosExportPipelinePort(),
        )
        IosAppServices(
            waterMarkRepo = waterMarkRepo,
            userConfigRepo = userConfigRepo,
            configBridge = IosWatermarkConfigBridge(waterMarkRepo),
            userConfigBridge = IosUserConfigBridge(userConfigRepo),
            session = session,
        )
    }
}
