package me.rosuh.easywatermark.session

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
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
import me.rosuh.easywatermark.shared.generated.resources.Res
import me.rosuh.easywatermark.shared.generated.resources.config_default_water_mark_text
import me.rosuh.easywatermark.ui.sharedString
import kotlin.experimental.ExperimentalObjCName
import kotlin.native.ObjCName

/** Locale-aware default — same key as Android/Desktop (`config_default_water_mark_text` / master). */
private fun defaultWatermarkText(): String = sharedString(Res.string.config_default_water_mark_text)

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
     * Public three-argument ObjC/Swift surface — do **not** add parameters here (framework ABI).
     *
     * @param pickGeneration token from [IosPickGenerationGate.nextPhotoGeneration] (Swift edge).
     * @return source path of the focused (last newly staged) image.
     */
    @Throws(Exception::class)
    suspend fun stagePickedImagesBytes(
        imageBytesList: List<ByteArray>,
        append: Boolean,
        pickGeneration: Long,
    ): String = stagePickedImagesBytesInternal(
        imageBytesList = imageBytesList,
        append = append,
        pickGeneration = pickGeneration,
        hostAlive = { true },
    ).focusPath

    /**
     * Result of a successful lifecycle-aware stage+publish.
     * [stagedPaths] are the exact `ewm_src_*` files written by **this** delivery (identity for cleanup).
     * [publishedSelectionUris] is the Session selection list that was published (order-preserving).
     */
    internal data class StagePublishResult(
        val focusPath: String,
        val stagedPaths: List<String>,
        val publishedSelectionUris: List<String>,
    )

    /**
     * Lifecycle-aware stage+publish for the Compose host.
     * **Not** exported to ObjC — keep [hostAlive] off the public framework surface.
     */
    internal suspend fun stagePickedImagesBytesInternal(
        imageBytesList: List<ByteArray>,
        append: Boolean,
        pickGeneration: Long,
        hostAlive: () -> Boolean,
    ): StagePublishResult {
        require(imageBytesList.isNotEmpty()) { "stagePickedImagesBytes: empty list" }
        // Phase 1 — file-first stage with bounded concurrency (G4).
        // Gather by input index so Session selection order matches picker order.
        // Caller's multi ByteArray list is not retained after return; durable identity is path.
        // stageBytesBounded is fail-closed: any partial multi-item failure deletes siblings written so far.
        val staged = stageBytesBounded(imageBytesList)
        try {
            if (!IosPickGenerationGate.isPhotoCurrent(pickGeneration) || !hostAlive()) {
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

            // Phase 2 — single atomic EnterEditor(+SelectCurrent) publish if still current
            // **and** host is still alive (disposed host must not mutate Session).
            val focusUri =
                if (focus != null && selected.firstOrNull()?.uri != focus.uri) focus.uri else null
            val published = session.publishEditorSelectionIf(
                stillValid = {
                    IosPickGenerationGate.isPhotoCurrent(pickGeneration) && hostAlive()
                },
                selected = selected,
                waterMark = wm,
                focusUriIfNotFirst = focusUri,
            )
            if (!published) {
                throw StalePickGenerationException(pickGeneration)
            }
            val stagedPaths = staged.map { it.uri.value }
            return StagePublishResult(
                focusPath = stagedPaths.last(),
                stagedPaths = stagedPaths,
                publishedSelectionUris = selected.map { it.uri.value },
            )
        } catch (e: Throwable) {
            // Any unsuccessful publication path: delete newly staged but unpublished files.
            staged.forEach { IosSourceStager.deleteQuietly(it.uri.value) }
            throw e
        }
    }

    /**
     * Stage [imageBytesList] to `ewm_src_*` files with at most [IOS_STAGING_MAX_CONCURRENCY]
     * concurrent writers. Returns [ImageInfo] list in **input order**.
     *
     * Fail-closed / cancellation-safe:
     * - Each child reports success/failure without cancelling siblings mid-handoff.
     * - After a durable write, ownership registration runs under [NonCancellable] so a cancelled
     *   sibling cannot escape [writtenPaths] before outer cleanup.
     */
    private suspend fun stageBytesBounded(imageBytesList: List<ByteArray>): List<ImageInfo> {
        val writtenPaths = mutableListOf<String>()
        val writtenLock = Mutex()
        suspend fun trackNonCancellable(path: String) {
            withContext(NonCancellable) {
                writtenLock.withLock { writtenPaths.add(path) }
            }
        }
        suspend fun cleanupWritten() {
            val snapshot = withContext(NonCancellable) {
                writtenLock.withLock { writtenPaths.toList() }
            }
            snapshot.forEach { IosSourceStager.deleteQuietly(it) }
        }
        try {
            if (imageBytesList.size == 1) {
                val path = IosSourceStager.stageBytes(imageBytesList.first())
                try {
                    IosStageWriteProbe.awaitAfterWrite(path)
                } finally {
                    trackNonCancellable(path)
                }
                return listOf(ImageInfo(MediaRef(path)))
            }
            val gate = Semaphore(IOS_STAGING_MAX_CONCURRENCY)
            // supervisorScope: one child's failure does not cancel siblings mid-track.
            val childResults = supervisorScope {
                imageBytesList.mapIndexed { index, bytes ->
                    async(Dispatchers.Default) {
                        gate.withPermit {
                            IosStageConcurrencyProbe.onEnter()
                            try {
                                val path = IosSourceStager.stageBytes(bytes)
                                try {
                                    IosStageWriteProbe.awaitAfterWrite(path)
                                } finally {
                                    // Always register after durable write, even if cancelled/failed in probe.
                                    trackNonCancellable(path)
                                }
                                Result.success(index to ImageInfo(MediaRef(path)))
                            } catch (t: Throwable) {
                                Result.failure(t)
                            } finally {
                                IosStageConcurrencyProbe.onExit()
                            }
                        }
                    }
                }.map { it.await() }
            }
            val firstFailure = childResults.firstOrNull { it.isFailure }
            if (firstFailure != null) {
                throw firstFailure.exceptionOrNull()
                    ?: CancellationException("stageBytesBounded child failed")
            }
            return childResults
                .map { it.getOrThrow() }
                .sortedBy { it.first }
                .map { it.second }
        } catch (e: Throwable) {
            cleanupWritten()
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

@OptIn(kotlin.experimental.ExperimentalNativeApi::class)
private fun installKotlinUnhandledExceptionLogging() {
    // Print the real throwable before K/N's default terminate/SIGABRT path.
    setUnhandledExceptionHook { t ->
        println("K/N UNHANDLED: ${t::class.simpleName}: ${t.message}")
        t.printStackTrace()
    }
}

private object IosAppServicesHolder {
    val instance: IosAppServices by lazy {
        me.rosuh.easywatermark.ui.StartupTrace.mark("app_create_start")
        installKotlinUnhandledExceptionLogging()
        val waterMarkRepo = WaterMarkRepository(
            dataStore = createWaterMarkDataStore(),
            defaultTextProvider = { defaultWatermarkText() },
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
        ).also {
            me.rosuh.easywatermark.ui.StartupTrace.mark("app_create_end")
        }
    }
}
