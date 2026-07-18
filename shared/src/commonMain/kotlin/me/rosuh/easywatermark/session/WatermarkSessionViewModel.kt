package me.rosuh.easywatermark.session

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
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
 * Offset→export (narrow, KMP-safe):
 * - [applyOffset] is the **sole** offset entry: sync repo CAS then session CAS before return.
 *   Call from UI/Main (or single-threaded hosts). No async [AppIntent] dual path.
 * - Non-export intents: [sessionMutex] serializes one intent's **reduceAndPublish + executeEffect**
 *   so they do not interleave with another intent's critical section. Mutex is mutual exclusion
 *   only — it does **not** guarantee fire-and-forget [dispatch] FIFO across concurrent launchers.
 * - Reducer publish writes launch via [MutableStateFlow.update] + pure [mergeLaunchPreservingLiveImages]
 *   so a concurrent [applyOffset] is not lost on final write.
 * - [requestExport] resolves once (repo list first); [exportAndAwait] joins its own job.
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
     * Serializes non-export reduce + effects (coroutine Mutex — not JVM synchronized).
     * Mutual exclusion only; not a FIFO queue for independent [dispatch] launchers.
     */
    private val sessionMutex = Mutex()
    private var exportJob: Job? = null

    init {
        viewModelScope.launch(Dispatchers.Default) {
            waterMarkRepo.waterMark.collect { wm ->
                resetJobStatus()
                applyIntent(AppIntent.SyncWaterMark(wm))
            }
        }
        viewModelScope.launch(Dispatchers.Default) {
            waterMarkRepo.selectedImage.collect {
                applyIntent(AppIntent.SyncCurrentImage(waterMarkRepo.selectedImage.value))
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
                // Full non-export critical section: reduce+publish then effects of one intent
                // do not interleave with another. Production UI awaits editor entry before select.
                sessionMutex.withLock {
                    val effects = reduceAndPublish(intent)
                    for (effect in effects) {
                        executeEffect(effect)
                    }
                }
            }
        }
    }

    /**
     * Snapshot read → reduce → write on [Dispatchers.Main.immediate] with no suspend between.
     * Launch is published with CAS [MutableStateFlow.update] so concurrent [applyOffset] is merged.
     */
    private suspend fun reduceAndPublish(intent: AppIntent): List<SessionEffect> {
        var effects: List<SessionEffect> = emptyList()
        withContext(Dispatchers.Main.immediate) {
            val before = currentSnapshot()
            val effective = when (intent) {
                is AppIntent.SyncCurrentImage ->
                    AppIntent.SyncCurrentImage(waterMarkRepo.selectedImage.value)
                else -> intent
            }
            val result = reduceSessionUi(before, effective)
            effects = result.effects
            _launchScreenUiStateFlow.update { current ->
                mergeLaunchPreservingLiveImages(
                    reduced = result.snapshot.launch,
                    live = current,
                    before = before.launch,
                )
            }
            _galleryPickedImageList.value = result.snapshot.galleryPicked
            _uiState.value = result.snapshot.dialogUi
        }
        return effects
    }

    private fun currentSnapshot(): SessionUiSnapshot = SessionUiSnapshot(
        launch = _launchScreenUiStateFlow.value,
        galleryPicked = _galleryPickedImageList.value,
        dialogUi = _uiState.value,
    )

    /**
     * Freeze export inputs from committed sources. Repo list is offset truth (post-[applyOffset]);
     * session list is fallback for any observation window; caller object last.
     */
    private fun resolveExportImages(requested: List<ImageInfo>): List<ImageInfo> {
        if (requested.isEmpty()) return emptyList()
        val repoList = waterMarkRepo.imageInfoList
        val sessionList = _launchScreenUiStateFlow.value.selectedImageList
        return requested.map { req ->
            repoList.firstOrNull { it.uri == req.uri }
                ?: sessionList.firstOrNull { it.uri == req.uri }
                ?: req
        }
    }

    private suspend fun applyConfigChange(change: WatermarkConfigChange) {
        when (change) {
            is WatermarkConfigChange.Text -> configEditor.updateText(change.text)
            is WatermarkConfigChange.Icon -> configEditor.updateIcon(change.icon)
            is WatermarkConfigChange.Color -> configEditor.updateTextColor(change.color)
            is WatermarkConfigChange.AlphaPercent -> configEditor.updateAlpha(change.percent)
            is WatermarkConfigChange.Degree -> configEditor.updateDegree(change.degree)
            is WatermarkConfigChange.TextSize -> configEditor.updateTextSize(change.size)
            is WatermarkConfigChange.Typeface -> configEditor.updateTextTypeface(change.typeface)
            is WatermarkConfigChange.TileMode -> configEditor.updateTileMode(change.tileMode)
            is WatermarkConfigChange.HorizontalGap -> configEditor.updateHorizon(change.gap)
            is WatermarkConfigChange.VerticalGap -> configEditor.updateVertical(change.gap)
        }
    }

    private suspend fun executeEffect(effect: SessionEffect) {
        when (effect) {
            is SessionEffect.CommitImageSelection -> commitImageSelection(effect.list)
            is SessionEffect.SelectImage -> {
                if (waterMarkRepo.selectedImage.value.uri != effect.ref) {
                    waterMarkRepo.select(effect.ref)
                }
            }
        }
    }

    private suspend fun commitImageSelection(list: List<ImageInfo>) {
        if (list.isEmpty()) return
        // Install list first so select(first) resolves to the same list entry (not a temp ImageInfo).
        waterMarkRepo.updateImageList(list)
        nextSelectedPos = 0
        waterMarkRepo.select(list.first().uri)
    }

    fun resetJobStatus() {
        waterMarkRepo.imageInfoList.forEach {
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
            ),
        )
    }

    protected fun setExportJobState(state: ExportJobState) {
        _exportJobState.value = state
    }

    fun requestExport(images: List<ImageInfo>) {
        val resolved = resolveExportImages(images)
        dispatch(AppIntent.RequestExport(resolved))
    }

    fun cancelExport() {
        dispatch(AppIntent.CancelExport)
    }

    suspend fun dispatchAndAwait(intent: AppIntent) = applyIntent(intent)

    suspend fun exportAndAwait(images: List<ImageInfo>) {
        val resolved = resolveExportImages(images)
        startExport(resolved)?.join()
    }

    private fun startExport(images: List<ImageInfo>): Job? {
        val pipeline = exportPipeline ?: return null
        exportJob?.cancel()
        val job = viewModelScope.launch(Dispatchers.Default) {
            if (images.isEmpty()) {
                setExportJobState(ExportJobState())
                return@launch
            }
            resetJobStatus()
            setExportJobState(
                ExportJobState(
                    isSaving = true,
                    totalCount = images.size,
                ),
            )
            val config = waterMarkRepo.waterMark.first()
            val prefs = userConfigRepo.userPreferences.first()
            for (info in images) {
                try {
                    info.jobState = JobState.Ing
                    setExportJobState(
                        ExportJobState(
                            isSaving = true,
                            completedCount = images.count { it.jobState is JobState.Success },
                            totalCount = images.size,
                        ),
                    )
                    val result = pipeline.exportOne(info, config, prefs)
                    info.result = result
                    info.jobState = if (result.isSuccess()) {
                        JobState.Success(result)
                    } else {
                        JobState.Failure(result)
                    }
                } catch (e: Exception) {
                    val failure = Result.failure<MediaRef>(
                        null,
                        code = ExportErrorCodes.FILE_NOT_FOUND,
                        message = e.message ?: "export failed",
                    )
                    info.result = failure
                    info.jobState = JobState.Failure(failure)
                }
                setExportJobState(
                    ExportJobState(
                        isSaving = true,
                        completedCount = images.count { it.jobState is JobState.Success },
                        totalCount = images.size,
                    ),
                )
            }
            setExportJobState(
                ExportJobState(
                    isFinished = true,
                    completedCount = images.count { it.jobState is JobState.Success },
                    totalCount = images.size,
                ),
            )
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
    ) = dispatch(AppIntent.EnterEditor(selected, gallerySnapshot, waterMark))

    fun applyConfig(change: WatermarkConfigChange) = dispatch(AppIntent.ApplyConfig(change))

    fun applyTextStyle(style: TextPaintStyle) = dispatch(AppIntent.ApplyTextStyle(style))

    /**
     * Synchronous offset commit — sole production entry (UI/Main callers only).
     *
     * Repo CAS first (fact source), then session launch CAS with the **same** committed object.
     * Missing URI is a no-op (does not install caller as curImageInfo).
     * Do not invent a second async [AppIntent] path or cross-thread fire-and-forget dual write.
     */
    fun applyOffset(info: ImageInfo) {
        val committed = configEditor.updateOffset(info) ?: return
        _launchScreenUiStateFlow.update { current ->
            applyCurrentImageToLaunch(current, committed)
        }
    }
}
