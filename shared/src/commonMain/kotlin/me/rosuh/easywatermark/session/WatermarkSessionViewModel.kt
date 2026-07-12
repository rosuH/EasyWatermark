package me.rosuh.easywatermark.session

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
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
 * **Phase 1:** launch/gallery/editor route, gallery multi-select, template [UiState], selection commit.
 * **Phase 2:** batch export orchestration via [ExportPipelinePort] (Android wraps native generateImage).
 *
 * Performance: reduce/export on [Dispatchers.Default]/[Dispatchers.IO] inside ports; UI only observes
 * [StateFlow]. Android skill: UI → [dispatch] → state; no business state only in Compose `remember`.
 */
open class WatermarkSessionViewModel(
    protected val waterMarkRepo: WaterMarkRepository,
    protected val userConfigRepo: UserConfigRepository,
    exportPipeline: ExportPipelinePort? = null,
) : ViewModel() {

    /** Shared config editor (Phase 5); Android hosts no longer own the update* loop. */
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

    /** Mutable for Android filmstrip selection handoff (legacy nextSelectedPos). */
    var nextSelectedPos: Int = 0

    /**
     * Platform export implementation. Android sets this to [me.rosuh.easywatermark.session.AndroidExportPipelinePort]
     * (or injects via constructor). Null disables [requestExport].
     */
    protected var exportPipeline: ExportPipelinePort? = exportPipeline

    private val sessionMutex = Mutex()
    private var exportJob: Job? = null

    init {
        viewModelScope.launch(Dispatchers.Default) {
            waterMarkRepo.waterMark.collect { wm ->
                // Production v2.10 clears prior export results after any watermark edit.
                resetJobStatus()
                applyIntent(AppIntent.SyncWaterMark(wm))
            }
        }
        viewModelScope.launch(Dispatchers.Default) {
            waterMarkRepo.selectedImage.collect { info ->
                applyIntent(AppIntent.SyncCurrentImage(info))
            }
        }
    }

    fun dispatch(intent: AppIntent) {
        viewModelScope.launch(Dispatchers.Default) {
            applyIntent(intent)
        }
    }

    /**
     * Apply [intent]; export intents run outside the UI-snapshot mutex so long exports do not
     * block gallery/nav reduces.
     */
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
            is AppIntent.ApplyOffset -> {
                configEditor.updateOffset(intent.info)
            }
            else -> {
                sessionMutex.withLock {
                    val before = SessionUiSnapshot(
                        launch = _launchScreenUiStateFlow.value,
                        galleryPicked = _galleryPickedImageList.value,
                        dialogUi = _uiState.value,
                    )
                    val result = reduceSessionUi(before, intent)
                    publishSnapshot(result.snapshot)
                    for (effect in result.effects) {
                        executeEffect(effect)
                    }
                }
            }
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

    private suspend fun publishSnapshot(snapshot: SessionUiSnapshot) {
        withContext(Dispatchers.Main) {
            _launchScreenUiStateFlow.value = snapshot.launch
            _galleryPickedImageList.value = snapshot.galleryPicked
            _uiState.value = snapshot.dialogUi
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
        waterMarkRepo.select(list.first().uri)
        nextSelectedPos = 0
        waterMarkRepo.updateImageList(list)
    }

    fun resetJobStatus() {
        waterMarkRepo.imageInfoList.forEach {
            it.jobState = JobState.Ready
        }
        _exportJobState.value = ExportJobState()
    }

    protected fun setExportJobState(state: ExportJobState) {
        _exportJobState.value = state
    }

    /**
     * Batch export: one [ExportPipelinePort.exportOne] per item, progress on [exportJobState].
     * Wraps platform pipeline — does not reimplement raster.
     */
    fun requestExport(images: List<ImageInfo>) {
        dispatch(AppIntent.RequestExport(images))
    }

    fun cancelExport() {
        dispatch(AppIntent.CancelExport)
    }

    /** Apply a UI intent and wait (Desktop/iOS hosts that need sequenced enter-then-export). */
    suspend fun dispatchAndAwait(intent: AppIntent) = applyIntent(intent)

    /** Run batch export and wait until the job completes (or is cancelled). */
    suspend fun exportAndAwait(images: List<ImageInfo>) {
        startExport(images)?.join()
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
                    val result = pipeline.exportOne(info, config, prefs)
                    info.result = result
                    info.jobState = if (result.isSuccess()) {
                        JobState.Success(result)
                    } else {
                        JobState.Failure(result)
                    }
                } catch (e: Exception) {
                    // Ports should map known failures into Result; OOM is handled inside Android port.
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

    // --- Typed convenience API (mirrors legacy MainViewModel names for hosts) ---

    fun goTemplate() = dispatch(AppIntent.GoTemplate)
    fun resetEditDialog() = dispatch(AppIntent.ResetEditDialog)
    fun goTemplateEdit() = dispatch(AppIntent.GoEdit)
    fun useTemplate(template: Template) = dispatch(AppIntent.UseTemplate(template))
    fun goEditDialog() = dispatch(AppIntent.GoEditDialog)
    fun onBackPressed() = dispatch(AppIntent.NavigateBack)
    fun resetGalleryData() = dispatch(AppIntent.ResetGalleryData)

    fun selectImage(ref: MediaRef) = dispatch(AppIntent.SelectCurrent(ref))

    fun openGalleryWithImages(images: List<Image>) = dispatch(AppIntent.GalleryLoaded(images))

    fun enterEditor(
        selected: List<ImageInfo>,
        gallerySnapshot: List<Image> = emptyList(),
        waterMark: WaterMark = _launchScreenUiStateFlow.value.waterMark,
    ) = dispatch(AppIntent.EnterEditor(selected, gallerySnapshot, waterMark))

    fun applyConfig(change: WatermarkConfigChange) = dispatch(AppIntent.ApplyConfig(change))

    fun applyTextStyle(style: TextPaintStyle) = dispatch(AppIntent.ApplyTextStyle(style))

    fun applyOffset(info: ImageInfo) = dispatch(AppIntent.ApplyOffset(info))
}
