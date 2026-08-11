package me.rosuh.easywatermark.ui

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.widget.Toast
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.rosuh.easywatermark.BuildConfig
import me.rosuh.easywatermark.MyApp
import me.rosuh.easywatermark.shared.generated.resources.Res
import me.rosuh.easywatermark.shared.generated.resources.crash_mail
import me.rosuh.easywatermark.shared.generated.resources.email_subject
import me.rosuh.easywatermark.shared.generated.resources.tip_not_mail_found
import me.rosuh.easywatermark.data.model.ImageFormat
import me.rosuh.easywatermark.data.model.ImageInfo
import me.rosuh.easywatermark.data.model.MediaRef
import me.rosuh.easywatermark.data.model.TextPaintStyle
import me.rosuh.easywatermark.data.model.TextTypeface
import me.rosuh.easywatermark.data.model.UserPreferences
import me.rosuh.easywatermark.data.model.WaterMark
import me.rosuh.easywatermark.data.model.WatermarkTileMode
import me.rosuh.easywatermark.data.model.entity.Template
import me.rosuh.easywatermark.data.model.WatermarkConfigChange
import me.rosuh.easywatermark.data.repo.TemplateRepository
import me.rosuh.easywatermark.data.repo.UserConfigRepository
import me.rosuh.easywatermark.data.repo.WaterMarkRepository
import me.rosuh.easywatermark.domain.OutputPrefsEditor
import me.rosuh.easywatermark.domain.TemplateEditor
import me.rosuh.easywatermark.platform.AndroidIconPersistence
import me.rosuh.easywatermark.platform.AndroidIconSelectionCoordinator
import me.rosuh.easywatermark.session.AndroidExportPipelinePort
import me.rosuh.easywatermark.session.AndroidMediaLibraryPort
import me.rosuh.easywatermark.session.AppIntent
import me.rosuh.easywatermark.session.ExportErrorCodes
import me.rosuh.easywatermark.session.ExportJobState
import me.rosuh.easywatermark.session.MediaLibraryPort
import me.rosuh.easywatermark.session.WatermarkSessionViewModel
import me.rosuh.easywatermark.utils.ktx.formatDate
import me.rosuh.easywatermark.utils.ktx.launch
import me.rosuh.easywatermark.utils.ktx.toMediaRef
import me.rosuh.easywatermark.utils.ktx.toUri
import org.koin.java.KoinJavaComponent.inject

/** Android alias for shared [ExportJobState] (export-sheet presentation). */
typealias SaveExportUiState = ExportJobState

/**
 * Android product host over [WatermarkSessionViewModel] (ADR-0017).
 *
 * Maps legacy [Action] to [AppIntent]; owns MediaStore gallery query and crash export.
 * Pre-compress recovery UI dropped (ADR-0022) — OOM/export failures use recovery copy only.
 */
class MainViewModel (
    private val userRepo: UserConfigRepository,
    waterMarkRepo: WaterMarkRepository,
    private val templateRepo: TemplateRepository,
) : WatermarkSessionViewModel(
    waterMarkRepo = waterMarkRepo,
    userConfigRepo = userRepo,
) {

    // output-preference write use-case (still Android-hosted launch wrapper).
    private val outputPrefsEditor = OutputPrefsEditor(userRepo)

    // template add/update/delete business logic lives in a commonMain use-case; the VM keeps
    // UiState mapping (the null-DAO -> UiState.DatabaseError branch stays here). Built from the
    // already-injected template repo (no DI change).
    private val templateEditor = TemplateEditor(templateRepo)

    /** Android UI name for shared export progress [exportJobState]. */
    val saveExportUiState: StateFlow<SaveExportUiState> get() = exportJobState

    private var _userPreferences: StateFlow<UserPreferences> = userRepo.userPreferences.stateIn(
        viewModelScope,
        SharingStarted.Eagerly,
        UserPreferences.DEFAULT
    )

    val userPreferences: StateFlow<UserPreferences> = _userPreferences

    val outputFormat: ImageFormat
        get() = _userPreferences.value.outputFormat

    val compressLevel: Int
        get() = _userPreferences.value.compressLevel

    val templateListFlow: StateFlow<List<Template>> = templateRepo.getAllTemplate().stateIn(
        viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        emptyList()
    )

    private val applicationContext: Context by inject(Context::class.java)

    private val iconPersistence by lazy { AndroidIconPersistence(applicationContext) }
    private val iconSelectionCoordinator by lazy {
        AndroidIconSelectionCoordinator(
            store = iconPersistence,
            currentIcon = { waterMarkRepo.waterMark.first().iconUri },
            commitIcon = { ref ->
                dispatchAndAwait(AppIntent.ApplyConfig(WatermarkConfigChange.Icon(ref)))
            },
        )
    }
    private var iconImportJob: Job? = null

    init {
        // Phase 2: shared export loop uses Android port (wrap of legacy generateImage).
        exportPipeline = AndroidExportPipelinePort(appContext = applicationContext)
        // Media library port for gallery listing / picker enrichment (default app ContentResolver).
        mediaLibrary = AndroidMediaLibraryPort(applicationContext.contentResolver)
    }

    fun addTemplate(content: String) {
        if (templateEditor.isDaoNull()) {
            dispatch(AppIntent.DatabaseError)
            return
        }
        viewModelScope.launch {
            templateEditor.add(content)
        }
    }

    fun updateTemplate(template: Template) {
        viewModelScope.launch {
            templateEditor.update(template)
        }
    }

    fun deleteTemplate(template: Template) {
        viewModelScope.launch {
            templateEditor.delete(template)
        }
    }

    /**
 * Batch export entry — orchestration lives in [WatermarkSessionViewModel.requestExport];
 * [contentResolver] retained for API compatibility (port uses application ContentResolver).
     */
    @Suppress("UNUSED_PARAMETER")
    fun saveImage(
        contentResolver: ContentResolver,
        imageList: List<ImageInfo>,
    ) {
        requestExport(imageList)
    }

    /**
     * Gallery / Photo Picker / same-session share-in: enter Editor with the given content URIs
     * directly (no app-owned source copies, no process-death restore).
     */
    fun updateImageList(list: List<Uri>) {
        launch {
            generateImageInfoList(list)?.run {
                enterEditor(selected = this, waterMark = persistedWaterMark())
            }
        }
    }

    /**
     * ACTION_SEND / ACTION_SEND_MULTIPLE: same-session direct URI entry (no staging directory).
     * Process-death restoration of imported sources is intentionally unsupported.
     */
    fun enterEditorFromShareUris(list: List<Uri>) {
        updateImageList(list)
    }

    private suspend fun persistedWaterMark(): WaterMark = waterMarkRepo.waterMark.first()

    private suspend fun generateImageInfoList(list: List<Uri>) =
        withContext(Dispatchers.Default) {
            return@withContext list.toSet()
                .map { ImageInfo(it.toMediaRef()) }
                .takeIf {
                    it.isNotEmpty()
                }
        }

    fun updateText(text: String) =
        applyConfig(WatermarkConfigChange.Text(text))

    fun updateTextSize(textSize: Float) =
        applyConfig(WatermarkConfigChange.TextSize(textSize))

    fun updateTextColor(color: Int) =
        applyConfig(WatermarkConfigChange.Color(color))

    fun updateTextStyle(style: TextPaintStyle) = applyTextStyle(style)

    fun updateTextTypeface(typeface: TextTypeface) =
        applyConfig(WatermarkConfigChange.Typeface(typeface))

    fun updateAlpha(alpha: Float) =
        applyConfig(WatermarkConfigChange.AlphaPercent(alpha))

    fun updateHorizon(gap: Int) =
        applyConfig(WatermarkConfigChange.HorizontalGap(gap))

    fun updateVertical(gap: Int) =
        applyConfig(WatermarkConfigChange.VerticalGap(gap))

    fun updateDegree(degree: Float) =
        applyConfig(WatermarkConfigChange.Degree(degree))

    fun updateIcon(iconUri: MediaRef) =
        applyConfig(WatermarkConfigChange.Icon(iconUri))

    /** Copy a transient Photo Picker Uri, then atomically advance the persisted icon reference. */
    fun importWatermarkIcon(source: Uri) {
        iconImportJob?.cancel()
        iconImportJob = viewModelScope.launch {
            iconSelectionCoordinator.import(source)
        }
    }

    fun updateTileMode(tileMode: WatermarkTileMode) =
        applyConfig(WatermarkConfigChange.TileMode(tileMode))

    fun updateOffset(info: ImageInfo) = applyOffset(info)

    fun saveOutput(
        format: ImageFormat = _userPreferences.value.outputFormat,
        level: Int = _userPreferences.value.compressLevel
    ) {
        viewModelScope.launch {
            outputPrefsEditor.save(format, level)
        }
        resetJobStatus()
    }

    /** About switch: prefer in-app MediaStore gallery over system Photo Picker (Android). */
    fun setPreferInAppGallery(enabled: Boolean) {
        viewModelScope.launch {
            userRepo.updatePreferInAppGallery(enabled)
        }
    }

    /** ADR-0027: Content editor theme follows selected photo. */
    fun setFollowPhoto(enabled: Boolean) {
        viewModelScope.launch {
            userRepo.updateFollowPhoto(enabled)
        }
    }

    /**
     * E3: Session owns selection list. Remove one item and re-publish via EnterEditor
     * (or NavigateBack when empty). Does not call [WaterMarkRepository] list/select APIs.
     */
    fun removeImage(
        imageInfo: ImageInfo?,
        curSelectedPos: Int,
    ) {
        if (imageInfo == null) return
        val list = launchScreenUiStateFlow.value.selectedImageList.toMutableList()
        val removePos = list.indexOfFirst { it.uri == imageInfo.uri }
        if (removePos < 0) return
        list.removeAt(removePos)
        val selectedPos =
            if (removePos < curSelectedPos || removePos >= list.size) {
                (curSelectedPos - 1).coerceAtLeast(0)
            } else {
                curSelectedPos
            }
        launch {
            nextSelectedPos = selectedPos
            if (list.isEmpty()) {
                onBackPressed()
                return@launch
            }
            enterEditor(selected = list, waterMark = persistedWaterMark())
            if (removePos == curSelectedPos) {
                list.getOrNull(selectedPos)?.uri?.let { selectImage(it) }
            }
        }
    }

    fun extraCrashInfo(activity: Activity, crashInfo: String?) {
        // user do not saving crash info into external storage
        // So that wo just share the internal file
        val mainContent = """
Dear developer, here are my crash info:
```
$crashInfo
```
---

APP:

${BuildConfig.VERSION_CODE}, ${BuildConfig.VERSION_NAME}, ${BuildConfig.BUILD_TYPE}

Devices:

${Build.VERSION.RELEASE}, ${Build.VERSION.SDK_INT}, ${Build.DEVICE}, ${Build.MODEL}, ${Build.PRODUCT}, ${Build.MANUFACTURER}

${System.currentTimeMillis().formatDate("yyy-MM-dd")}
""".trimIndent()
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "message/rfc822"
            putExtra(Intent.EXTRA_EMAIL, arrayOf("hi@rosuh.me"))
            putExtra(Intent.EXTRA_SUBJECT, sharedString(Res.string.email_subject))
            putExtra(Intent.EXTRA_TEXT, mainContent)
        }
        try {
            activity.startActivity(
                Intent.createChooser(
                    intent,
                    sharedString(Res.string.crash_mail),
                )
            )
        } catch (e: ActivityNotFoundException) {
            e.printStackTrace()
            Toast.makeText(
                activity,
                sharedString(Res.string.tip_not_mail_found),
                Toast.LENGTH_LONG,
            ).show()
        }
    }

    override fun onCleared() {
        iconImportJob?.cancel()
        super.onCleared()
    }

    fun saveUpgradeInfo() {
        launch { userRepo.saveVersionCode(BuildConfig.VERSION_CODE) }
    }

    fun query(contentResolver: ContentResolver) {
        // Prefer the caller’s ContentResolver (Activity); session loadGallery uses [mediaLibrary].
        mediaLibrary = AndroidMediaLibraryPort(contentResolver)
        loadGallery()
    }

    fun selectGallery(selectedList: List<Image>) {
        val imageInfoList = selectedList.map { ImageInfo(it.uri) }
        if (imageInfoList.isNotEmpty()) {
            launch {
                enterEditor(
                    selected = imageInfoList,
                    gallerySnapshot = selectedList,
                    waterMark = persistedWaterMark(),
                )
            }
        }
    }

    fun process(action: Action) {
        when (action) {
            is Action.DialogDismiss -> {
                dispatch(AppIntent.DismissGallery(selected = action.isSelected))
            }

            is Action.LoadImages -> {
                query(action.resolver)
            }

            is Action.GalleryImageSelected -> {
                dispatch(
                    AppIntent.ToggleGalleryItem(
                        image = action.image,
                        index = action.index,
                        checked = action.isCheck,
                    ),
                )
            }

            is Action.EditorImageSelected -> {
                selectImage(action.image.uri)
            }

            is Action.SystemPickerImageSelected -> {
                launch(Dispatchers.Default) {
                    enterEditorFromSystemUris(action.uriList)
                }
            }
        }
    }

    /**
 * System Photo Picker URIs → session editor.
 * Always enter from the raw picker URIs; enrichment is best-effort metadata only and must
 * Not block selection when MediaStore join fails (partial access / non-MediaStore URIs).     */
    private suspend fun enterEditorFromSystemUris(uriList: List<Uri>) {
        if (uriList.isEmpty()) return
        val refs = uriList.map { it.toMediaRef() }
        val imageInfoList = refs.map { ImageInfo(it) }
        val library = mediaLibrary ?: AndroidMediaLibraryPort(applicationContext.contentResolver)
        val gallerySnapshot = runCatching { library.enrichPickerRefs(refs) }.getOrDefault(emptyList())
        enterEditor(
            selected = imageInfoList,
            gallerySnapshot = gallerySnapshot,
            waterMark = persistedWaterMark(),
        )
    }

    companion object {
        const val TYPE_ERROR_FILE_NOT_FOUND = ExportErrorCodes.FILE_NOT_FOUND
        const val TYPE_ERROR_SAVE_OOM = ExportErrorCodes.SAVE_OOM
    }
}
