package me.rosuh.easywatermark.session

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import me.rosuh.easywatermark.data.model.ImageInfo
import me.rosuh.easywatermark.data.model.JobState
import me.rosuh.easywatermark.data.model.MediaRef
import me.rosuh.easywatermark.data.model.WaterMark
import me.rosuh.easywatermark.data.model.entity.Template
import me.rosuh.easywatermark.data.repo.WaterMarkRepository
import me.rosuh.easywatermark.ui.Image
import me.rosuh.easywatermark.ui.LaunchScreenState
import me.rosuh.easywatermark.ui.UiState

/**
 * Shared product session host (ADR-0017).
 *
 * **Phase 1:** owns launch/gallery/editor route, gallery multi-select, template dialog [UiState],
 * and selection commit effects via [WaterMarkRepository]. Config edits still go through Editors
 * on the Android host; export orchestration is Phase 2.
 *
 * Performance: state reduces on [Dispatchers.Default]; UI observes [StateFlow] only.
 * Android skill: UI events → [dispatch] → state; no business state only in Compose `remember`.
 */
open class WatermarkSessionViewModel(
    protected val waterMarkRepo: WaterMarkRepository,
) : ViewModel() {

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

    private val sessionMutex = Mutex()

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
     * Apply [intent] on a background dispatcher; serialised so concurrent collects/dispatches
     * do not interleave snapshot writes.
     */
    protected suspend fun applyIntent(intent: AppIntent) {
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
}
