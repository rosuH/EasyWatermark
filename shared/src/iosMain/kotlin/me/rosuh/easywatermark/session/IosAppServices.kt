package me.rosuh.easywatermark.session

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
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
 * G4: max concurrent [IosSourceStager.stageBytes] writers during multi-pick stage.
 * Order of [ImageInfo] in Session is always input order (index-stable gather).
 */
const val IOS_STAGING_MAX_CONCURRENCY: Int = 3

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
        // Phase 1 — file-first stage with bounded concurrency (G4).
        // Gather by input index so Session selection order matches picker order.
        // Caller's multi ByteArray list is not retained after return; durable identity is path.
        val staged = stageBytesBounded(imageBytesList)
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

    /**
     * Stage [imageBytesList] to `ewm_src_*` files with at most [IOS_STAGING_MAX_CONCURRENCY]
     * concurrent writers. Returns [ImageInfo] list in **input order**.
     */
    private suspend fun stageBytesBounded(imageBytesList: List<ByteArray>): List<ImageInfo> {
        if (imageBytesList.size == 1) {
            return listOf(ImageInfo(MediaRef(IosSourceStager.stageBytes(imageBytesList.first()))))
        }
        val gate = Semaphore(IOS_STAGING_MAX_CONCURRENCY)
        return coroutineScope {
            imageBytesList.mapIndexed { index, bytes ->
                async(Dispatchers.Default) {
                    gate.withPermit {
                        IosStageConcurrencyProbe.onEnter()
                        try {
                            index to ImageInfo(MediaRef(IosSourceStager.stageBytes(bytes)))
                        } finally {
                            IosStageConcurrencyProbe.onExit()
                        }
                    }
                }
            }.awaitAll()
                .sortedBy { it.first }
                .map { it.second }
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
