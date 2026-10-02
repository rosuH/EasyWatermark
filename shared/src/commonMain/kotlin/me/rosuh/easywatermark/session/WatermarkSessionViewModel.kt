package me.rosuh.easywatermark.session

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import me.rosuh.easywatermark.data.model.ImageInfo
import me.rosuh.easywatermark.data.model.JobState
import me.rosuh.easywatermark.data.model.MediaRef
import me.rosuh.easywatermark.data.model.Result
import me.rosuh.easywatermark.data.model.TextPaintStyle
import me.rosuh.easywatermark.data.model.WaterMark
import me.rosuh.easywatermark.data.model.WatermarkConfigChange
import me.rosuh.easywatermark.data.model.entity.Template
import me.rosuh.easywatermark.data.repo.UserConfigRepository
import me.rosuh.easywatermark.data.repo.WaterMarkRepository
import me.rosuh.easywatermark.domain.WatermarkConfigEditor
import me.rosuh.easywatermark.ui.Image
import me.rosuh.easywatermark.ui.LaunchScreenState
import me.rosuh.easywatermark.ui.UiState

/**
 * Shared product session host (ADR-0017).
 *
 * Offset→export (narrow, KMP-safe) — E1:
 * - [applyOffset] is the **sole** offset entry: pure Session CAS on list+cur (same identity).
 * Call from UI/Main (or single-threaded hosts).
 * - Non-export intents: [sessionMutex] serializes one intent's **reduceAndPublish**
 * So they do not interleave with another intent's critical section. Mutex is mutual exclusion * only — it does **not** guarantee fire-and-forget [dispatch] FIFO across concurrent launchers.
 * - Reducer publish writes launch via [MutableStateFlow.update] + pure [mergeLaunchPreservingLiveImages]
 * so a concurrent [applyOffset] is not lost on final write.
 * - [requestExport] freezes images from the Session snapshot only; [exportAndAwait] joins its own job.
 */
open class WatermarkSessionViewModel(
    protected val waterMarkRepo: WaterMarkRepository,
    protected val userConfigRepo: UserConfigRepository,
    exportPipeline: ExportPipelinePort? = null,
) : ViewModel() {

    protected val configEditor = WatermarkConfigEditor(waterMarkRepo)

    private val _uiState: MutableStateFlow<UiState> = MutableStateFlow(UiState.None)
    val uiStateFlow: StateFlow<UiState> = _uiState.asStateFlow()

    private val _launchScreenUiStateFlow: MutableStateFlow<LaunchScreenState> =
        MutableStateFlow(LaunchScreenState())
    val launchScreenUiStateFlow: StateFlow<LaunchScreenState> = _launchScreenUiStateFlow.asStateFlow()

    private val _galleryPickedImageList: MutableStateFlow<List<Image>?> = MutableStateFlow(null)
    val galleryPickedImageList: StateFlow<List<Image>?> = _galleryPickedImageList.asStateFlow()

    private val _exportJobState: MutableStateFlow<ExportJobState> = MutableStateFlow(ExportJobState())
    val exportJobState: StateFlow<ExportJobState> = _exportJobState.asStateFlow()

    var nextSelectedPos: Int = 0

    protected var exportPipeline: ExportPipelinePort? = exportPipeline

    protected var mediaLibrary: MediaLibraryPort? = null

    /**
 * Serializes non-export publication (coroutine Mutex — not JVM synchronized).
 * Mutual exclusion only; not a FIFO queue for independent [dispatch] launchers.
     */
    private val sessionMutex = Mutex()
    private var exportJob: Job? = null
    /** Bumps on each [startExport] so a cancelled job's finally cannot clobber a newer run. */
    private var exportGeneration: Int = 0

    init {
        viewModelScope.launch(Dispatchers.Default) {
            var hasSyncedInitialWatermark = false
            waterMarkRepo.waterMark.collect { wm ->
                // The first DataStore emission initializes Session config; it is not a user edit.
                // It may arrive after an immediate export and must not erase that export's result.
                if (hasSyncedInitialWatermark) {
                    resetJobStatus()
                }
                hasSyncedInitialWatermark = true
                applyIntent(AppIntent.SyncWaterMark(wm))
            }
        }
    }

    fun dispatch(intent: AppIntent) {
        viewModelScope.launch(Dispatchers.Default) {
            applyIntent(intent)
        }
    }

    protected suspend fun applyIntent(intent: AppIntent) {
        when (intent) {
            is AppIntent.RequestExport -> {
                startExport(intent.images)
            }
            AppIntent.CancelExport -> {
                exportJob?.cancel()
                exportJob = null
            }
            is AppIntent.ApplyConfig -> {
                applyConfigChange(intent.change)
            }
            is AppIntent.ApplyTextStyle -> {
                configEditor.updateTextStyle(intent.style)
            }
            else -> {
                // Production UI awaits editor entry before select.
                sessionMutex.withLock {
                    reduceAndPublish(intent)
                }
            }
        }
    }

    /**
 * Snapshot read → reduce → write on [Dispatchers.Main.immediate] with no suspend between.
 * Launch is published with CAS [MutableStateFlow.update] so concurrent [applyOffset] is merged.
     */
    private suspend fun reduceAndPublish(intent: AppIntent) {
        withContext(Dispatchers.Main.immediate) {
            val before = currentSnapshot()
            val result = reduceSessionUi(before, intent)
            if (result !== before &&
                (intent is AppIntent.EnterEditor || intent is AppIntent.DismissGallery && intent.selected)
            ) {
                nextSelectedPos = 0
            }
            _launchScreenUiStateFlow.update { current ->
                mergeLaunchPreservingLiveImages(
                    reduced = result.launch,
                    live = current,
                    before = before.launch,
                )
            }
            _galleryPickedImageList.value = result.galleryPicked
            _uiState.value = result.dialogUi
        }
    }

    private fun currentSnapshot(): SessionUiSnapshot = SessionUiSnapshot(
        launch = _launchScreenUiStateFlow.value,
        galleryPicked = _galleryPickedImageList.value,
        dialogUi = _uiState.value,
    )

    /**
     * Freeze export inputs from the Session snapshot only (E1).
     * Session list is offset/selection truth; caller object last if URI unknown to Session.
     */
    private fun resolveExportImages(requested: List<ImageInfo>): List<ImageInfo> {
        if (requested.isEmpty()) return emptyList()
        val sessionList = _launchScreenUiStateFlow.value.selectedImageList
        return requested.map { req ->
            sessionList.firstOrNull { it.uri == req.uri } ?: req
        }
    }

    private suspend fun applyConfigChange(change: WatermarkConfigChange) {
        when (change) {
            is WatermarkConfigChange.Text -> configEditor.updateText(change.text)
            is WatermarkConfigChange.Icon -> configEditor.updateIcon(change.icon)
            is WatermarkConfigChange.MarkMode -> configEditor.updateMarkMode(change.mode)
            is WatermarkConfigChange.Color -> configEditor.updateTextColor(change.color)
            is WatermarkConfigChange.AlphaPercent -> configEditor.updateAlpha(change.percent)
            is WatermarkConfigChange.Degree -> configEditor.updateDegree(change.degree)
            is WatermarkConfigChange.TextSize -> configEditor.updateTextSize(change.size)
            is WatermarkConfigChange.Typeface -> configEditor.updateTextTypeface(change.typeface)
            is WatermarkConfigChange.TextStyle -> configEditor.updateTextStyle(change.style)
            is WatermarkConfigChange.TileMode -> configEditor.updateTileMode(change.tileMode)
            is WatermarkConfigChange.HorizontalGap -> configEditor.updateHorizon(change.gap)
            is WatermarkConfigChange.VerticalGap -> configEditor.updateVertical(change.gap)
        }
    }

    fun resetJobStatus() {
        // E1: product export path owns job flags on Session list entries.
        _launchScreenUiStateFlow.value.selectedImageList.forEach {
            it.jobState = JobState.Ready
        }
        _exportJobState.value = ExportJobState()
    }

    fun markExportFinished(completedCount: Int, totalCount: Int) {
        setExportJobState(
            ExportJobState(
                isFinished = true,
                completedCount = completedCount,
                totalCount = totalCount,
                successCount = completedCount,
                failureCount = 0,
                processedCount = completedCount,
            ),
        )
    }

    protected fun setExportJobState(state: ExportJobState) {
        _exportJobState.value = state
    }

    fun requestExport(images: List<ImageInfo>) {
        val resolved = resolveExportImages(images)
        // Eager isSaving on the caller's frame (UI thread) so the export sheet cannot dismiss
        // and bottom actions cannot unmount under the Retry finger before Default dispatch runs.
        val priorSuccess = resolved.count { it.jobState is JobState.Success }
        setExportJobState(
            ExportJobState(
                isSaving = true,
                isFinished = false,
                completedCount = priorSuccess,
                totalCount = resolved.size,
                successCount = priorSuccess,
                failureCount = 0,
                processedCount = priorSuccess,
            ),
        )
        dispatch(AppIntent.RequestExport(resolved))
    }

    /**
     * Cancel the in-flight export job **synchronously** (D2).
     * Does not wait for a Default-dispatcher dispatch hop; the job is cancelled immediately so
     * cooperative cancel is observed at the next suspend point in the export loop.
     */
    fun cancelExport() {
        exportJob?.cancel()
        exportJob = null
    }

    suspend fun dispatchAndAwait(intent: AppIntent) = applyIntent(intent)

    /**
     * Generation-scoped EnterEditor (+ optional SelectCurrent) publication (F12/F16).
     *
     * Under [sessionMutex], a single [stillValid] check gates all selection StateFlow writes
     * on [Dispatchers.Main.immediate], with no suspension between the check and publication.
     * When [stillValid] is false, nothing is written. No publish-then-rollback.
     *
     * @return true if published; false if skipped.
     */
    suspend fun publishEditorSelectionIf(
        stillValid: () -> Boolean,
        selected: List<ImageInfo>,
        waterMark: WaterMark,
        focusUriIfNotFirst: MediaRef? = null,
        gallerySnapshot: List<Image> = emptyList(),
    ): Boolean {
        return sessionMutex.withLock {
            var published = false
            withContext(Dispatchers.Main.immediate) {
                // Single validity check immediately before any StateFlow write.
                if (!stillValid()) return@withContext
                // Same selection-boundary reset as [enterEditor] (iOS progressive import path).
                if (!_exportJobState.value.isSaving) {
                    resetJobStatus()
                }
                val before = currentSnapshot()
                val enter = reduceSessionUi(
                    before,
                    AppIntent.EnterEditor(
                        selected = selected,
                        gallerySnapshot = gallerySnapshot,
                        waterMark = waterMark,
                    ),
                )
                var mid = enter

                if (
                    focusUriIfNotFirst != null &&
                    selected.firstOrNull()?.uri != focusUriIfNotFirst
                ) {
                    val select = reduceSessionUi(
                        mid,
                        AppIntent.SelectCurrent(focusUriIfNotFirst),
                    )
                    mid = select
                }

                if (selected.isNotEmpty()) nextSelectedPos = 0

                _launchScreenUiStateFlow.update { current ->
                    mergeLaunchPreservingLiveImages(
                        reduced = mid.launch,
                        live = current,
                        before = before.launch,
                    )
                }
                _galleryPickedImageList.value = mid.galleryPicked
                _uiState.value = mid.dialogUi
                published = true
            }
            published
        }
    }

    /**
     * Apply a config change only if [stillValid] is true immediately before the write (F16 icon).
     * Does not roll back a completed DataStore write; callers must not treat a false return as
     * "config was never attempted" when [stillValid] flipped during a suspending store edit —
     * icon host code re-checks generation after return and skips host-side bind when stale.
     */
    suspend fun applyConfigIf(
        stillValid: () -> Boolean,
        change: WatermarkConfigChange,
    ): Boolean {
        return sessionMutex.withLock {
            if (!stillValid()) return@withLock false
            applyConfigChange(change)
            true
        }
    }

    suspend fun exportAndAwait(images: List<ImageInfo>) {
        val resolved = resolveExportImages(images)
        startExport(resolved)?.join()
    }

    private fun startExport(images: List<ImageInfo>): Job? {
        val pipeline = exportPipeline ?: return null
        val generation = ++exportGeneration
        exportJob?.cancel()
        val job = viewModelScope.launch(Dispatchers.Default) {
            if (images.isEmpty()) {
                if (generation == exportGeneration) setExportJobState(ExportJobState())
                return@launch
            }
            // D2 retry policy: preserve prior Success (no double-export); clear stuck Ing;
            // re-queue Failure as Ready so retry can re-run failed items only.
            for (info in images) {
                when (info.jobState) {
                    is JobState.Ing -> info.jobState = JobState.Ready
                    is JobState.Failure -> {
                        info.jobState = JobState.Ready
                        info.result = null
                    }
                    else -> Unit
                }
            }
            var successCount = images.count { it.jobState is JobState.Success }
            var failureCount = 0
            var cancelledInFlight = 0
            fun publishExportState(saving: Boolean, finished: Boolean) {
                if (generation != exportGeneration) return
                val processed = successCount + failureCount + cancelledInFlight
                setExportJobState(
                    ExportJobState(
                        isSaving = saving,
                        isFinished = finished,
                        completedCount = successCount,
                        totalCount = images.size,
                        successCount = successCount,
                        failureCount = failureCount,
                        processedCount = processed,
                    ),
                )
            }
            try {
                publishExportState(saving = true, finished = false)
                val config = waterMarkRepo.waterMark.first()
                val prefs = userConfigRepo.userPreferences.first()
                for (info in images) {
                    // Do not re-export items that already succeeded (retry-after-cancel).
                    if (info.jobState is JobState.Success) continue
                    // Cooperative cancel: stop starting later items.
                    ensureActive()
                    try {
                        info.jobState = JobState.Ing
                        publishExportState(saving = true, finished = false)
                        // D1: consume typed ExportOutcome; bridge to Result<MediaRef> for hosts until D5.
                        val outcome = pipeline.exportOne(info, config, prefs)
                        when (outcome) {
                            is ExportOutcome.Success -> {
                                val media = outcome.media
                                info.width = media.width
                                info.height = media.height
                                val result = outcome.toLegacyResult()
                                info.result = result
                                info.jobState = JobState.Success(result)
                                successCount += 1
                            }
                            is ExportOutcome.Failure -> {
                                val result = outcome.toLegacyResult()
                                info.result = result
                                info.jobState = JobState.Failure(result)
                                failureCount += 1
                            }
                        }
                    } catch (e: CancellationException) {
                        // Never map cancel to FILE_NOT_FOUND. Leave item terminal if it was in-flight.
                        if (info.jobState is JobState.Ing) {
                            val cancelled = Result.failure<MediaRef>(
                                null,
                                code = ExportErrorCodes.CANCELLED,
                                message = e.message ?: "export cancelled",
                            )
                            info.result = cancelled
                            info.jobState = JobState.Failure(cancelled)
                            cancelledInFlight += 1
                        }
                        throw e
                    } catch (e: Exception) {
                        val failure = Result.failure<MediaRef>(
                            null,
                            code = ExportErrorCodes.FILE_NOT_FOUND,
                            message = e.message ?: "export failed",
                        )
                        info.result = failure
                        info.jobState = JobState.Failure(failure)
                        failureCount += 1
                    }
                    publishExportState(saving = true, finished = false)
                }
            } finally {
                // Always leave isSaving and clear any stuck Ing (cancel / failure / success).
                for (info in images) {
                    if (info.jobState is JobState.Ing) {
                        val cancelled = Result.failure<MediaRef>(
                            null,
                            code = ExportErrorCodes.CANCELLED,
                            message = "export cancelled",
                        )
                        info.result = cancelled
                        info.jobState = JobState.Failure(cancelled)
                        cancelledInFlight += 1
                    }
                }
                successCount = images.count { it.jobState is JobState.Success }
                failureCount = images.count {
                    it.jobState is JobState.Failure &&
                        it.result?.code != ExportErrorCodes.CANCELLED
                }
                cancelledInFlight = images.count {
                    it.jobState is JobState.Failure &&
                        it.result?.code == ExportErrorCodes.CANCELLED
                }
                // Cancelled in-flight counts toward failureCount for taxonomy honesty in processed
                // split: processed = success + non-cancel failure + cancel; expose cancel inside
                // failureCount? Plan: success / failure / processed. Count cancel as processed
                // via cancelledInFlight folded into processedCount; failureCount = hard failures only.
                publishExportState(saving = false, finished = true)
            }
        }
        exportJob = job
        return job
    }

    fun goTemplate() = dispatch(AppIntent.GoTemplate)
    fun resetEditDialog() = dispatch(AppIntent.ResetEditDialog)
    fun goTemplateEdit() = dispatch(AppIntent.GoEdit)
    fun useTemplate(template: Template) = dispatch(AppIntent.UseTemplate(template))
    fun goEditDialog() = dispatch(AppIntent.GoEditDialog)
    fun onBackPressed() = dispatch(AppIntent.NavigateBack)

    /** E0: open About from Launch (default) or Editor. */
    fun openAbout(returnTo: me.rosuh.easywatermark.ui.LaunchScreenUiState) =
        dispatch(AppIntent.OpenAbout(returnTo = returnTo))
    fun resetGalleryData() = dispatch(AppIntent.ResetGalleryData)

    fun selectImage(ref: MediaRef) = dispatch(AppIntent.SelectCurrent(ref))

    fun openGalleryWithImages(images: List<Image>) = dispatch(AppIntent.GalleryLoaded(images))

    fun loadGallery() {
        val library = mediaLibrary ?: return
        viewModelScope.launch(Dispatchers.Default) {
            val images = library.listImages()
            applyIntent(AppIntent.GalleryLoaded(images))
        }
    }

    fun enterEditor(
        selected: List<ImageInfo>,
        gallerySnapshot: List<Image> = emptyList(),
        waterMark: WaterMark = _launchScreenUiStateFlow.value.waterMark,
    ) {
        // New selection batch must not inherit prior export finished/success chrome.
        // Never wipe an in-flight export; filmstrip focus uses SelectCurrent (not this path).
        if (!_exportJobState.value.isSaving) {
            resetJobStatus()
        }
        dispatch(AppIntent.EnterEditor(selected, gallerySnapshot, waterMark))
    }

    fun applyConfig(change: WatermarkConfigChange) = dispatch(AppIntent.ApplyConfig(change))

    fun applyTextStyle(style: TextPaintStyle) = dispatch(AppIntent.ApplyTextStyle(style))

    /**
     * Synchronous offset commit — sole production entry (UI/Main callers only).
     *
     * E1: pure Session CAS on [launchScreenUiStateFlow]. List entry and cur share the same
     * committed object when URI matches. Offset-only: preserves dims/job flags from the
     * existing list entry; does not mutate the caller object. Missing URI is a no-op.
     * Do not invent a second async [AppIntent] path or cross-thread fire-and-forget dual write.
     */
    fun applyOffset(info: ImageInfo) {
        _launchScreenUiStateFlow.update { current ->
            val index = current.selectedImageList.indexOfFirst { it.uri == info.uri }
            if (index < 0) return@update current
            val existing = current.selectedImageList[index]
            if (existing.offsetX == info.offsetX && existing.offsetY == info.offsetY) {
                // Same offsets → keep existing list identity; rebind cur if needed.
                return@update if (
                    current.curImageInfo?.uri == existing.uri &&
                    current.curImageInfo !== existing
                ) {
                    current.copy(curImageInfo = existing)
                } else {
                    current
                }
            }
            val committed = existing.copy(
                offsetX = info.offsetX,
                offsetY = info.offsetY,
            )
            val newList = current.selectedImageList.toMutableList().also { it[index] = committed }
            val newCur =
                if (current.curImageInfo?.uri == committed.uri) committed else current.curImageInfo
            current.copy(
                selectedImageList = newList,
                curImageInfo = newCur,
            )
        }
    }
}
