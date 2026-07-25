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
import kotlin.experimental.ExperimentalObjCName
import kotlin.native.ObjCName

private const val DEFAULT_WATERMARK_TEXT = "EasyWatermark 水印"

/**
 * Single-process iOS graph (ADR-0017 Phase 4): **one** watermark DataStore + user prefs store,
 * Shared by config bridges and [WatermarkSessionViewModel] (DataStore forbids dual stores per file).
 */
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
     * Stage + export (legacy WatermarkWorkflow.render path). Issues a **new** process-wide photo
     * generation so this path never hard-codes gen 0 after live picks (F14).
     */
    @Throws(Exception::class)
    suspend fun exportPickedImageBytes(imageBytes: ByteArray): String {
        val gen = IosPickGenerationGate.nextPhotoGeneration()
        stagePickedImagesBytes(listOf(imageBytes), append = false, pickGeneration = gen)
        return exportFocusedPreview()
    }

    /**
     * Fast path: prepare temp files, then **atomically** publish selection into Session only if
     * [pickGeneration] is still current (F12 — no publish-then-rollback).
     *
     * @param pickGeneration token from [IosPickGenerationGate.nextPhotoGeneration] (Swift edge).
     * @return source path of the focused (last newly staged) image.
     */
    @Throws(Exception::class)
    suspend fun stagePickedImagesBytes(
        imageBytesList: List<ByteArray>,
        append: Boolean,
        pickGeneration: Long,
    ): String {
        require(imageBytesList.isNotEmpty()) { "stagePickedImagesBytes: empty list" }
        // Phase 1 — prepare files (safe if later superseded; cleaned on stale).
        val staged = imageBytesList.map { bytes ->
            ImageInfo(MediaRef(IosSourceStager.stageBytes(bytes)))
        }
        try {
            if (!IosPickGenerationGate.isPhotoCurrent(pickGeneration)) {
                throw StalePickGenerationException(pickGeneration)
            }
            val prevLaunch = session.launchScreenUiStateFlow.first()
            val existing = if (append) prevLaunch.selectedImageList else emptyList()
            val previousCur = if (append) prevLaunch.curImageInfo else null
            val selected = me.rosuh.easywatermark.ui.ProductShellNav.mergePickedSelection(
                existing = existing,
                newly = staged,
                append = append,
            )
            val focus = me.rosuh.easywatermark.ui.ProductShellNav.focusAfterPick(
                selected = selected,
                append = append,
                previousCur = previousCur,
            )
            val wm = waterMarkRepo.waterMark.first()

            // Deterministic test seam: pause *outside* the session lock, immediately before
            // the guarded publication (F12). Production leaves the probe null.
            IosPickPublishProbe.awaitBeforeGuardedPublish(pickGeneration)

            // Phase 2 — single atomic EnterEditor(+SelectCurrent) publish if still current.
            // No StateFlow write when stale (no A, no rollback).
            val focusUri =
                if (focus != null && selected.firstOrNull()?.uri != focus.uri) focus.uri else null
            val published = session.publishEditorSelectionIf(
                stillValid = { IosPickGenerationGate.isPhotoCurrent(pickGeneration) },
                selected = selected,
                waterMark = wm,
                focusUriIfNotFirst = focusUri,
            )
            if (!published) {
                throw StalePickGenerationException(pickGeneration)
            }
            return staged.last().uri.value
        } catch (e: StalePickGenerationException) {
            staged.forEach { IosSourceStager.deleteQuietly(it.uri.value) }
            throw e
        }
    }

    @Throws(Exception::class)
    suspend fun exportFocusedPreview(): String {
        val launch = session.launchScreenUiStateFlow.first()
        val focus = launch.curImageInfo
            ?: launch.selectedImageList.firstOrNull()
            ?: error("exportFocusedPreview: no current image")
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

    @Throws(Exception::class)
    suspend fun exportPickedImagesBytes(
        imageBytesList: List<ByteArray>,
        append: Boolean,
        pickGeneration: Long,
    ): String {
        stagePickedImagesBytes(imageBytesList, append, pickGeneration)
        return exportFocusedPreview()
    }
}

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
