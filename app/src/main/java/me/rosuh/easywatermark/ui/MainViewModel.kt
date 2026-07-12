package me.rosuh.easywatermark.ui

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.ContentResolver
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
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
import me.rosuh.easywatermark.R
import me.rosuh.easywatermark.data.model.FuncTitleModel
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
import me.rosuh.easywatermark.domain.WatermarkConfigEditor
import me.rosuh.easywatermark.session.AndroidExportPipelinePort
import me.rosuh.easywatermark.session.AppIntent
import me.rosuh.easywatermark.session.ExportErrorCodes
import me.rosuh.easywatermark.session.ExportJobState
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
 * Android product host (ADR-0017 Phase 1–2): extends shared [WatermarkSessionViewModel] for
 * launch/gallery/editor session + export orchestration; [AndroidExportPipelinePort] wraps native
 * generateImage; compress/crash remain Android-only edges.
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

    // S4d-96: the neutral watermark config-edit logic now lives in the commonMain use-case; this VM
    // just owns the coroutine scope and delegates. Built from the already-injected repo (no DI change).
    private val configEditor = WatermarkConfigEditor(waterMarkRepo)

    // S4d-97: the output-preference write (format + compress level) lives in a commonMain use-case too,
    // built from the already-injected user repo (no DI change).
    private val outputPrefsEditor = OutputPrefsEditor(userRepo)

    // S4d-98: template add/update/delete business logic lives in a commonMain use-case; the VM keeps
    // UiState mapping (the null-DAO -> UiState.DatabaseError branch stays here). Built from the
    // already-injected template repo (no DI change).
    private val templateEditor = TemplateEditor(templateRepo)

    // S4d-65: StateFlow-only (was MutableLiveData). null initial = "no
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

    private val projection = arrayOf(
        MediaStore.Images.Media._ID,
        MediaStore.Images.Media.BUCKET_ID,
        MediaStore.Images.Media.BUCKET_DISPLAY_NAME,
        MediaStore.Images.Media.DATA,
        if (Build.VERSION.SDK_INT > 28) MediaStore.Images.Media.DATE_MODIFIED else MediaStore.Images.Media.DATE_TAKEN,
        MediaStore.Images.Media.ORIENTATION,
        MediaStore.Images.Media.WIDTH,
        MediaStore.Images.Media.HEIGHT,
        MediaStore.Images.Media.SIZE
    )

    val templateListFlow: StateFlow<List<Template>> = templateRepo.getAllTemplate().stateIn(
        viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        emptyList()
    )

    private val applicationContext: Context by inject(Context::class.java)

    init {
        // Phase 2: shared export loop uses Android port (wrap of legacy generateImage).
        exportPipeline = AndroidExportPipelinePort(appContext = applicationContext)
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

    fun updateText(text: String) {
        launch {
            configEditor.updateText(text)
        }
    }

    fun updateTextSize(textSize: Float) {
        launch {
            configEditor.updateTextSize(textSize)
        }
    }

    fun updateTextColor(color: Int) {
        launch {
            configEditor.updateTextColor(color)
        }
    }

    fun updateTextStyle(style: TextPaintStyle) {
        launch {
            configEditor.updateTextStyle(style)
        }
    }

    fun updateTextTypeface(typeface: TextTypeface) {
        launch {
            configEditor.updateTextTypeface(typeface)
        }
    }

    fun updateAlpha(alpha: Float) {
        launch {
            configEditor.updateAlpha(alpha)
        }
    }

    fun updateHorizon(gap: Int) {
        launch {
            configEditor.updateHorizon(gap)
        }
    }

    fun updateVertical(gap: Int) {
        launch {
            configEditor.updateVertical(gap)
        }
    }

    fun updateDegree(degree: Float) {
        launch {
            configEditor.updateDegree(degree)
        }
    }

    fun updateIcon(iconUri: MediaRef) {
        launch {
            configEditor.updateIcon(iconUri)
        }
    }

    fun updateTileMode(tileMode: WatermarkTileMode) {
        launch {
            configEditor.updateTileMode(tileMode)
        }
    }

    fun updateOffset(info: ImageInfo) {
        launch {
            configEditor.updateOffset(info)
        }
    }

    fun saveOutput(
        format: ImageFormat = _userPreferences.value.outputFormat,
        level: Int = _userPreferences.value.compressLevel
    ) {
        viewModelScope.launch {
            outputPrefsEditor.save(format, level)
        }
        resetJobStatus()
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
            putExtra(Intent.EXTRA_SUBJECT, activity.getString(R.string.email_subject))
            putExtra(Intent.EXTRA_TEXT, mainContent)
        }
        try {
            activity.startActivity(
                Intent.createChooser(
                    intent,
                    activity.getString(R.string.crash_mail)
                )
            )
        } catch (e: ActivityNotFoundException) {
            e.printStackTrace()
            Toast.makeText(
                activity,
                activity.getString(R.string.tip_not_mail_found),
                Toast.LENGTH_LONG
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
        launch {
            queryInternal(contentResolver)
        }
    }

    private suspend fun queryInternal(
        contentResolver: ContentResolver,
        force: Boolean = true,
    ) = withContext(Dispatchers.IO) {
        if (!force) {
            return@withContext
        }
        val list = ArrayList<Image>()
        contentResolver.query(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            projection,
            null,
            null,
            (if (Build.VERSION.SDK_INT > 28) MediaStore.Images.Media.DATE_MODIFIED else MediaStore.Images.Media.DATE_TAKEN) + " DESC"
        )?.use { cursor ->
            val imageIdColumn = cursor.getColumnIndex(MediaStore.Images.Media._ID)
            val bucketIdColumn = cursor.getColumnIndex(MediaStore.Images.Media.BUCKET_ID)
            val bucketNameColumn =
                cursor.getColumnIndex(MediaStore.Images.Media.BUCKET_DISPLAY_NAME)
            val dataColumn = cursor.getColumnIndex(MediaStore.Images.Media.DATA)
            val dateColumn =
                cursor.getColumnIndex(if (Build.VERSION.SDK_INT > 28) MediaStore.Images.Media.DATE_MODIFIED else MediaStore.Images.Media.DATE_TAKEN)
            val orientationColumn = cursor.getColumnIndex(MediaStore.Images.Media.ORIENTATION)
            val widthColumn = cursor.getColumnIndex(MediaStore.Images.Media.WIDTH)
            val heightColumn = cursor.getColumnIndex(MediaStore.Images.Media.HEIGHT)
            val sizeColumn = cursor.getColumnIndex(MediaStore.Images.Media.SIZE)

            while (cursor.moveToNext()) {
                val path = cursor.getString(dataColumn)
                if (path.isNullOrBlank()) {
                    continue
                }

                val imageId = cursor.getInt(imageIdColumn)
                val bucketId = cursor.getInt(bucketIdColumn)
                val bucketName = cursor.getString(bucketNameColumn) ?: ""
                val dateTaken = cursor.getLong(dateColumn)
                val orientation = cursor.getInt(orientationColumn)
                val width = cursor.getInt(widthColumn)
                val height = cursor.getInt(heightColumn)
                val size = cursor.getLong(sizeColumn)

                val contentUri: Uri = ContentUris.withAppendedId(
                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                    imageId.toLong()
                )

                // Stores column values and the contentUri in a local object
                // that represents the media file.
                val image = Image(imageId, contentUri.toMediaRef(), bucketName, size, dateTaken)
                list += image
            }
            // Session owns gallery dialog state (ADR-0017 Phase 1).
            openGalleryWithImages(list)
        }
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
                launch {
                    onWaterMarkChanged(action.item, action.any)
                }
            }

            is Action.EditorImageSelected -> {
                selectImage(action.image.uri)
            }

            is Action.SystemPickerImageSelected -> {
                launch(Dispatchers.Default) {
                    val newList = action.uriList
                    // map the uri to image (MediaStore metadata for gallerySnapshot optional)
                    val imageList = ArrayList<Image>()
                    val selection = "${MediaStore.Images.Media._ID} IN (${newList.joinToString(",") { "?" }})"
                    val selectionArgs = newList.map { ContentUris.parseId(it).toString() }.toTypedArray()
                    applicationContext.contentResolver.query(
                        MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                        projection,
                        selection,
                        selectionArgs,
                        (if (Build.VERSION.SDK_INT > 28) MediaStore.Images.Media.DATE_MODIFIED else MediaStore.Images.Media.DATE_TAKEN) + " DESC"
                    )?.use { cursor ->
                        val imageIdColumn = cursor.getColumnIndex(MediaStore.Images.Media._ID)
                        val bucketNameColumn =
                            cursor.getColumnIndex(MediaStore.Images.Media.BUCKET_DISPLAY_NAME)
                        val dataColumn = cursor.getColumnIndex(MediaStore.Images.Media.DATA)
                        val dateColumn =
                            cursor.getColumnIndex(if (Build.VERSION.SDK_INT > 28) MediaStore.Images.Media.DATE_MODIFIED else MediaStore.Images.Media.DATE_TAKEN)
                        val sizeColumn = cursor.getColumnIndex(MediaStore.Images.Media.SIZE)

                        while (cursor.moveToNext()) {
                            val path = cursor.getString(dataColumn)
                            if (path.isNullOrBlank()) {
                                continue
                            }

                            val imageId = cursor.getInt(imageIdColumn)
                            val bucketName =
                                cursor.getString(bucketNameColumn) ?: ""
                            val dateTaken = cursor.getLong(dateColumn)
                            val size = cursor.getLong(sizeColumn)

                            val contentUri: Uri = ContentUris.withAppendedId(
                                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                                imageId.toLong()
                            )

                            val image = Image(
                                imageId,
                                contentUri.toMediaRef(),
                                bucketName,
                                size,
                                dateTaken,
                                check = true
                            )
                            imageList += image
                        }
                    }
                    val imageInfoList = newList.map {
                        ImageInfo(it.toMediaRef())
                    }
                    enterEditor(
                        selected = imageInfoList,
                        gallerySnapshot = imageList,
                        waterMark = waterMarkFlow.value,
                    )
                }
            }
        }
    }

    private fun onWaterMarkChanged(item: FuncTitleModel, any: Any) {
        // S4d-72: map (FuncType, raw value) to a typed command at one shared boundary
        // (WatermarkConfigChange.from — fail-fast casts + gap rounding live there), then dispatch the
        // typed command to the existing update* methods (unchanged behavior source).
        when (val change = WatermarkConfigChange.from(item.type, any)) {
            is WatermarkConfigChange.Text -> updateText(change.text)
            // S4d-50: IconOption converts the picker Uri to MediaRef at the edge; here it is already MediaRef.
            is WatermarkConfigChange.Icon -> updateIcon(change.icon)
            is WatermarkConfigChange.Color -> updateTextColor(change.color)
            is WatermarkConfigChange.AlphaPercent -> updateAlpha(change.percent)
            is WatermarkConfigChange.Degree -> updateDegree(change.degree)
            is WatermarkConfigChange.TextSize -> updateTextSize(change.size)
            is WatermarkConfigChange.Typeface -> updateTextTypeface(change.typeface)
            is WatermarkConfigChange.TileMode -> updateTileMode(change.tileMode)
            is WatermarkConfigChange.HorizontalGap -> updateHorizon(change.gap)
            is WatermarkConfigChange.VerticalGap -> updateVertical(change.gap)
        }
    }

    companion object {
        const val TYPE_ERROR_FILE_NOT_FOUND = ExportErrorCodes.FILE_NOT_FOUND
        const val TYPE_ERROR_SAVE_OOM = ExportErrorCodes.SAVE_OOM
        const val TYPE_COMPRESS_ERROR = "type_CompressError"
        const val TYPE_COMPRESS_OK = "type_CompressOK"
        const val TYPE_COMPRESSING = "type_Compressing"
    }
}
