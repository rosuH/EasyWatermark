package me.rosuh.easywatermark.ui

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.widget.Toast
import androidx.core.content.FileProvider
import androidx.lifecycle.viewModelScope
import id.zelory.compressor.Compressor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.rosuh.easywatermark.BuildConfig
import me.rosuh.easywatermark.MyApp
import me.rosuh.easywatermark.data.model.FuncTitleModel
import me.rosuh.easywatermark.shared.generated.resources.Res
import me.rosuh.easywatermark.shared.generated.resources.crash_mail
import me.rosuh.easywatermark.shared.generated.resources.email_subject
import me.rosuh.easywatermark.shared.generated.resources.tip_not_mail_found
import me.rosuh.easywatermark.data.model.ImageFormat
import me.rosuh.easywatermark.data.model.ImageInfo
import me.rosuh.easywatermark.data.model.MediaRef
import me.rosuh.easywatermark.data.model.Result
import me.rosuh.easywatermark.data.model.TextPaintStyle
import me.rosuh.easywatermark.data.model.TextTypeface
import me.rosuh.easywatermark.data.model.UserPreferences
import me.rosuh.easywatermark.data.model.WaterMark
import me.rosuh.easywatermark.data.model.WatermarkTileMode
import me.rosuh.easywatermark.data.model.entity.Template
import me.rosuh.easywatermark.data.model.WatermarkConfigChange
import me.rosuh.easywatermark.data.repo.MemorySettingRepo
import me.rosuh.easywatermark.data.repo.TemplateRepository
import me.rosuh.easywatermark.data.repo.UserConfigRepository
import me.rosuh.easywatermark.data.repo.WaterMarkRepository
import me.rosuh.easywatermark.domain.OutputPrefsEditor
import me.rosuh.easywatermark.domain.TemplateEditor
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
import java.io.File

/** Android alias for shared [ExportJobState] (export-sheet presentation). */
typealias SaveExportUiState = ExportJobState

/**
 * Android product host over [WatermarkSessionViewModel] (ADR-0017).
 *
 * Maps legacy [Action] to [AppIntent]; owns MediaStore gallery query, compress, and crash export.
 */
class MainViewModel (
    private val userRepo: UserConfigRepository,
    waterMarkRepo: WaterMarkRepository,
    private val memorySettingRepo: MemorySettingRepo,
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

    // StateFlow-only (was MutableLiveData). null initial = "no
    // compress event yet" (old LiveData had no value before first emit). Distinct Result instances each
    // emit, so StateFlow conflation never skips a real event.
    private val _compressedResult = MutableStateFlow<Result<*>?>(null)
    val compressedResult: StateFlow<Result<*>?> = _compressedResult.asStateFlow()

    val waterMarkFlow = waterMarkRepo.waterMark.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5000L),
        WaterMark.default
    )

    /** Android UI name for shared export progress [exportJobState]. */
    val saveExportUiState: StateFlow<SaveExportUiState> get() = exportJobState

    val selectedImageFlow = waterMarkRepo.selectedImage

    private var compressedJob: Job? = null

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

    fun updateImageList(list: List<Uri>) {
        launch {
            generateImageInfoList(list)?.run {
                enterEditor(selected = this, waterMark = waterMarkFlow.value)
            }
        }
    }

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

    fun removeImage(
        imageInfo: ImageInfo?,
        curSelectedPos: Int,
    ) {
        val list = waterMarkRepo.imageInfoList.toMutableList()
        val removePos = list.indexOf(imageInfo)
        list.removeAt(removePos)
        val selectedPos =
            if (removePos < curSelectedPos || removePos >= waterMarkRepo.imageInfoList.size - 1
            ) {
                (curSelectedPos - 1).coerceAtLeast(0)
            } else {
                curSelectedPos
            }
        launch {
            nextSelectedPos = selectedPos
            waterMarkRepo.updateImageList(list)
            if (removePos == curSelectedPos) {
                list.getOrNull(selectedPos)?.uri?.let { selectImage(it) }
            }
        }
    }

    fun clearData() {
        launch {
            waterMarkRepo.select(MediaRef.Empty)
        }
    }

    fun compressImg(activity: Activity) {
        compressedJob = viewModelScope.launch(Dispatchers.IO) {
            _compressedResult.value = Result.success(null, code = TYPE_COMPRESSING)
            val tmpFile = File.createTempFile("easy_water_mark_", "_compressed")
            activity.contentResolver.openInputStream(waterMarkRepo.imageInfoList.first().uri.toUri())
                .use { input ->
                    tmpFile.outputStream().use { output ->
                        input?.copyTo(output)
                    }
                }
            val compressedFile = Compressor.compress(activity, tmpFile)
            // clear tmp files
            if (tmpFile.exists()) {
                tmpFile.delete()
            }
            try {
                val compressedFileUri = FileProvider.getUriForFile(
                    activity,
                    "${BuildConfig.APPLICATION_ID}.fileprovider",
                    compressedFile
                )
                selectImage(compressedFileUri.toMediaRef())
                _compressedResult.value = Result.success(null, code = TYPE_COMPRESS_OK)
            } catch (ie: IllegalArgumentException) {
                _compressedResult.value =
                    Result.failure(
                        null,
                        code = TYPE_COMPRESS_ERROR,
                        message = "Images creates uri failed."
                    )
            }
        }
    }

    fun cancelCompressJob() {
        compressedJob?.cancel()
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
        cancelCompressJob()
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
            enterEditor(
                selected = imageInfoList,
                gallerySnapshot = selectedList,
                waterMark = waterMarkFlow.value,
            )
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

            is Action.WaterMarkChange -> {
                // Edge: FuncTitleModel + raw value → shared typed config command.
                applyConfig(WatermarkConfigChange.from(action.item.type, action.any))
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
            waterMark = waterMarkFlow.value,
        )
    }

    companion object {
        const val TYPE_ERROR_FILE_NOT_FOUND = ExportErrorCodes.FILE_NOT_FOUND
        const val TYPE_ERROR_SAVE_OOM = ExportErrorCodes.SAVE_OOM
        const val TYPE_COMPRESS_ERROR = "type_CompressError"
        const val TYPE_COMPRESS_OK = "type_CompressOK"
        const val TYPE_COMPRESSING = "type_Compressing"
    }
}
