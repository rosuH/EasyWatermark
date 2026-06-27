package me.rosuh.easywatermark.ui

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.ContentResolver
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.media.MediaScannerConnection
import android.text.TextPaint
import android.util.Log
import android.widget.Toast
import androidx.core.content.FileProvider
import androidx.lifecycle.ViewModel
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
import me.rosuh.easywatermark.data.model.WatermarkConfigChange
import me.rosuh.easywatermark.data.model.ImageFormat
import me.rosuh.easywatermark.data.model.ImageInfo
import me.rosuh.easywatermark.data.model.JobState
import me.rosuh.easywatermark.data.model.MediaRef
import me.rosuh.easywatermark.data.model.Result
import me.rosuh.easywatermark.data.model.TextPaintStyle
import me.rosuh.easywatermark.data.model.TextTypeface
import me.rosuh.easywatermark.data.model.UserPreferences
import me.rosuh.easywatermark.data.model.WaterMark
import me.rosuh.easywatermark.data.model.WatermarkMode
import me.rosuh.easywatermark.data.model.WatermarkTileMode
import me.rosuh.easywatermark.data.model.entity.Template
import me.rosuh.easywatermark.render.WatermarkRenderer
import me.rosuh.easywatermark.render.androidTextMeasureEnv
import me.rosuh.easywatermark.data.repo.MemorySettingRepo
import me.rosuh.easywatermark.data.repo.TemplateRepository
import me.rosuh.easywatermark.data.repo.UserConfigRepository
import me.rosuh.easywatermark.data.repo.WaterMarkRepository
import me.rosuh.easywatermark.domain.OutputPrefsEditor
import me.rosuh.easywatermark.domain.WatermarkConfigEditor
import me.rosuh.easywatermark.utils.FileUtils.Companion.outPutFolderName
import me.rosuh.easywatermark.utils.bitmap.decodeBitmapFromUri
import me.rosuh.easywatermark.utils.bitmap.decodeSampledBitmapFromResource
import me.rosuh.easywatermark.utils.ktx.applyConfig
import me.rosuh.easywatermark.utils.ktx.formatDate
import me.rosuh.easywatermark.utils.ktx.toCompressFormat
import me.rosuh.easywatermark.utils.ktx.toMediaRef
import me.rosuh.easywatermark.utils.ktx.toUri
import me.rosuh.easywatermark.utils.ktx.launch
import me.rosuh.easywatermark.utils.ktx.obtainTileMode
import org.koin.java.KoinJavaComponent.inject
import java.io.File
import java.io.FileNotFoundException
import java.io.FileOutputStream
import kotlin.time.Clock

class MainViewModel (
    private val userRepo: UserConfigRepository,
    private val waterMarkRepo: WaterMarkRepository,
    private val memorySettingRepo: MemorySettingRepo,
    private val templateRepo: TemplateRepository,
) : ViewModel() {

    var nextSelectedPos: Int = 0

    // S4d-96: the neutral watermark config-edit logic now lives in the commonMain use-case; this VM
    // just owns the coroutine scope and delegates. Built from the already-injected repo (no DI change).
    private val configEditor = WatermarkConfigEditor(waterMarkRepo)

    // S4d-97: the output-preference write (format + compress level) lives in a commonMain use-case too,
    // built from the already-injected user repo (no DI change).
    private val outputPrefsEditor = OutputPrefsEditor(userRepo)

    // S4d-64: StateFlow-only (was MutableLiveData). null initial = "no save event yet", matching the old
    // LiveData (no value until first emit). Distinct Result instances each emit, so StateFlow conflation
    // never skips a real event.
    private val _saveResult = MutableStateFlow<Result<*>?>(null)
    val saveResult: StateFlow<Result<*>?> = _saveResult.asStateFlow()

    // S4d-65: StateFlow-only (was MutableLiveData), mirroring saveResult (S4d-64). null initial = "no
    // compress event yet" (old LiveData had no value before first emit). Distinct Result instances each
    // emit, so StateFlow conflation never skips a real event.
    private val _compressedResult = MutableStateFlow<Result<*>?>(null)
    val compressedResult: StateFlow<Result<*>?> = _compressedResult.asStateFlow()

    val waterMarkFlow = waterMarkRepo.waterMark.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5000L),
        WaterMark.default
    )

    private val uiState: MutableStateFlow<UiState> = MutableStateFlow(UiState.None)

    val uiStateFlow: StateFlow<UiState> = uiState.asStateFlow()

    private val _launchScreenUiStateFlow: MutableStateFlow<LaunchScreenState> =
        MutableStateFlow(LaunchScreenState.default())
    val launchScreenUiStateFlow = _launchScreenUiStateFlow.asStateFlow()

    // S4d-69: StateFlow-only (was the last MutableLiveData). Nullable with null initial preserves the old
    // LiveData "no value yet" vs empty-list distinction that the `value ?: return` / `?: emptyList()` reads
    // rely on. Android Image/Uri payload stays at this Android UI edge (only read internally in MainViewModel).
    private val _galleryPickedImageList = MutableStateFlow<List<Image>?>(null)
    val galleryPickedImageList: StateFlow<List<Image>?> = _galleryPickedImageList.asStateFlow()

    val selectedImageFlow = waterMarkRepo.selectedImage

    // S4d-66: StateFlow-only (was MutableLiveData). null initial = "no save in progress", matching the old
    // LiveData (no value before first emit). `ImageInfo` is a MUTABLE data class and the writers mutate the
    // SAME instance (jobState/result) then emit it; a StateFlow set of the same reference would be conflated
    // (distinctUntilChanged) and SKIP progress updates. `emitSaveProcess` snapshots via `copy()` so every
    // non-null progress update is a distinct value that StateFlow always emits.
    private val _saveProcess = MutableStateFlow<ImageInfo?>(null)
    val saveProcess: StateFlow<ImageInfo?> = _saveProcess.asStateFlow()

    private fun emitSaveProcess(info: ImageInfo?) {
        _saveProcess.value = info?.copy()
    }

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
        launch {
            withContext(Dispatchers.Default) {
                waterMarkFlow.collect {
                    val nextState = launchScreenUiStateFlow.value.copy(waterMark = it)
                    withContext(Dispatchers.Main) {
                        _launchScreenUiStateFlow.emit(nextState)
                    }
                }
            }
        }
        launch(Dispatchers.Default) {
            selectedImageFlow.collect {
                val nextState = launchScreenUiStateFlow.value.copy(curImageInfo = it)
                withContext(Dispatchers.Main) {
                    _launchScreenUiStateFlow.emit(nextState)
                }
            }
        }
    }

    fun addTemplate(content: String) {
        if (templateRepo.checkIfIsDaoNull()) {
            launch {
                uiState.emit(UiState.DatabaseError)
            }
            return
        }
        viewModelScope.launch {
            val template = Template(
                0,
                content = content,
                creationDate = Clock.System.now(),
                lastModifiedDate = Clock.System.now()
            )
            templateRepo.insertTemplate(template)
        }
    }

    fun updateTemplate(template: Template) {
        viewModelScope.launch {
            templateRepo.updateTemplate(template)
        }
    }

    fun deleteTemplate(template: Template) {
        viewModelScope.launch {
            templateRepo.deleteTemplate(template)
        }
    }

    fun saveImage(
        contentResolver: ContentResolver,
        imageList: List<ImageInfo>,
    ) {
        viewModelScope.launch {
            if (imageList.isEmpty()) {
                _saveResult.value = Result.failure(null, code = TYPE_ERROR_NOT_IMG)
                return@launch
            }
            _saveResult.value =
                Result.success(null, code = TYPE_SAVING)
            val result = generateList(contentResolver, imageList)
            if (result.isFailure()) {
                _saveResult.value = Result.failure(null, code = TYPE_ERROR_FILE_NOT_FOUND)
                return@launch
            }
            _saveResult.value = Result.success(code = TYPE_JOB_FINISH, data = result.data)
        }
    }

    private suspend fun generateList(
        contentResolver: ContentResolver,
        infoList: List<ImageInfo>?,
    ): Result<List<ImageInfo>> =
        withContext(Dispatchers.Default) {
            if (infoList.isNullOrEmpty()) {
                return@withContext Result.failure(null, TYPE_ERROR_NOT_IMG)
            }
            infoList.forEach { info ->
                try {
                    info.jobState = JobState.Ing
                    // S4d-66 r1: snapshot BEFORE dispatching to Main. `info` keeps mutating on this
                    // (Default) dispatcher (to Success below), so copying inside the Main lambda could
                    // capture the later state. Capture the Ing snapshot here so the progress update is
                    // the intended value.
                    val inProgress = info.copy()
                    launch(Dispatchers.Main) { emitSaveProcess(inProgress) }
                    info.result = generateImage(contentResolver, info)
                    info.jobState = JobState.Success(info.result!!)
                    val success = info.copy()
                    launch(Dispatchers.Main) { emitSaveProcess(success) }
                } catch (fne: FileNotFoundException) {
                    fne.printStackTrace()
                    info.result = Result.failure(null, code = TYPE_ERROR_FILE_NOT_FOUND)
                    info.jobState = JobState.Failure(info.result!!)
                    emitSaveProcess(info)
                } catch (oom: OutOfMemoryError) {
                    info.result = Result.failure(null, code = TYPE_ERROR_SAVE_OOM)
                    info.jobState = JobState.Failure(info.result!!)
                    emitSaveProcess(info)
                }
                Log.i("generateList", "${info.uri} : ${info.result}")
            }
            // reset process state
            emitSaveProcess(null)
            return@withContext Result.success(infoList)
        }

    private suspend fun generateImage(
        contentResolver: ContentResolver,
        imageInfo: ImageInfo,
    ): Result<Uri> =
        withContext(Dispatchers.IO) {
            val rect = decodeBitmapFromUri(contentResolver, imageInfo.uri.toUri())
            if (rect.isFailure()) {
                return@withContext Result.extendMsg(rect)
            }
            val mutableBitmap = rect.data?.bitmap?.copy(Bitmap.Config.ARGB_8888, true)
                ?: return@withContext Result.failure(
                    null,
                    code = "-1",
                    message = "Copy bitmap from uri failed."
                )

            imageInfo.width = mutableBitmap.width
            imageInfo.height = mutableBitmap.height
            val tmpConfig = waterMarkFlow.value ?: return@withContext Result.failure(
                null,
                code = "-1",
                message = "config.value == null"
            )
            val canvas = Canvas(mutableBitmap)
            // Export sizing is image-space; preview matrix values are not needed here.
            val bitmapPaint = TextPaint().applyConfig(imageInfo, tmpConfig, isScale = false)
            val layoutPaint = Paint()
            // S2a: build the cell shader through the Android renderer seam (the same
            // WatermarkRenderer the Compose preview uses).
            val shader = when (waterMarkFlow.value?.markMode) {
                WatermarkMode.Text -> {
                    WatermarkRenderer.buildTextShader(
                        imageInfo,
                        waterMarkFlow.value!!,
                        bitmapPaint,
                        androidTextMeasureEnv(applicationContext),
                        Dispatchers.IO
                    )
                }

                WatermarkMode.Image -> {
                    // Decode the icon against source-image bounds so export is independent of preview size.
                    // S4d-50: iconUri is now a platform-neutral MediaRef; convert to Uri at the decode edge.
                    val iconBitmapRect = decodeSampledBitmapFromResource(
                        contentResolver,
                        tmpConfig.iconUri.toUri(),
                        imageInfo.width,
                        imageInfo.height
                    )
                    if (iconBitmapRect.isFailure() || iconBitmapRect.data == null) {
                        return@withContext Result.failure(
                            null,
                            code = "-1",
                            message = "decodeSampledBitmapFromResource == null"
                        )
                    }
                    val iconBitmap = iconBitmapRect.data!!.bitmap
                    WatermarkRenderer.buildIconShader(
                        imageInfo,
                        iconBitmap,
                        tmpConfig,
                        bitmapPaint,
                        scale = true,
                        Dispatchers.IO
                    )
                }

                null -> return@withContext Result.failure(
                    null,
                    code = "-1",
                    message = "Unknown markmode"
                )
            }

            // S2a: composition delegated to the shared renderer seam (same helper as preview).
            // Export composites at the bitmap origin (left/top = 0, region = full bitmap); the old
            // REPEAT branch had no canvas translate, which `compose` reproduces as translate(0,0).
            WatermarkRenderer.compose(
                canvas = canvas,
                shader = shader,
                tileMode = tmpConfig.obtainTileMode(),
                paint = layoutPaint,
                left = 0f,
                top = 0f,
                regionWidth = mutableBitmap.width.toFloat(),
                regionHeight = mutableBitmap.height.toFloat(),
                offsetX = imageInfo.offsetX,
                offsetY = imageInfo.offsetY,
            )

            return@withContext if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val imageCollection =
                    MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
                val imageDetail = ContentValues().apply {
                    put(
                        MediaStore.Images.Media.DISPLAY_NAME,
                        generateOutputName()
                    )
                    put(MediaStore.Images.Media.MIME_TYPE, "image/${trapOutputExtension()}")
                    put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/$outPutFolderName/")
                    put(MediaStore.Images.Media.IS_PENDING, 1)
                }

                val imageContentUri = contentResolver.insert(imageCollection, imageDetail)
                contentResolver.openFileDescriptor(imageContentUri!!, "w", null).use { pfd ->
                    mutableBitmap.compress(
                        outputFormat.toCompressFormat(),
                        compressLevel,
                        FileOutputStream(pfd!!.fileDescriptor)
                    )
                }
                imageDetail.clear()
                imageDetail.put(MediaStore.Images.Media.IS_PENDING, 0)
                contentResolver.update(imageContentUri, imageDetail, null, null)
                Result.success(imageContentUri)
            } else {
                // need request write_storage permission
                // should check Pictures folder exist
                val picturesFile: File =
                    Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES)
                        ?: return@withContext Result.failure(
                            null,
                            code = "-1",
                            message = "Can't get pictures directory."
                        )
                if (!picturesFile.exists()) {
                    picturesFile.mkdir()
                }
                val mediaDir = File(picturesFile, outPutFolderName)

                if (!mediaDir.exists()) {
                    mediaDir.mkdirs()
                }
                val outputFile =
                    File(mediaDir, generateOutputName())
                outputFile.outputStream().use { fileOutputStream ->
                    mutableBitmap.compress(
                        outputFormat.toCompressFormat(),
                        compressLevel,
                        fileOutputStream
                    )
                }
                val outputUri = FileProvider.getUriForFile(
                    MyApp.instance,
                    "${BuildConfig.APPLICATION_ID}.fileprovider",
                    outputFile
                )
                MediaScannerConnection.scanFile(
                    MyApp.instance,
                    arrayOf(outputFile.absolutePath),
                    null,
                    null
                )
                Result.success(outputUri)
            }
        }

    private fun generateOutputName(): String {
        return "ewm_${System.currentTimeMillis()}.${trapOutputExtension()}"
    }

    private fun trapOutputExtension(): String {
        return if (outputFormat == ImageFormat.PNG) "png" else "jpg"
    }

    fun selectImage(ref: MediaRef) {
        if (selectedImageFlow.value?.uri == ref) {
            return
        }
        launch {
            waterMarkRepo.select(ref)
        }
    }

    fun updateImageList(list: List<Uri>) {
        launch {
            generateImageInfoList(list)?.run {
                updateImageListInternal(this)
            }
        }
    }

    private fun updateImageListInternal(list: List<ImageInfo>) {
        launch {
            waterMarkRepo.select(list.first().uri)
            nextSelectedPos = 0
            waterMarkRepo.updateImageList(list)
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

    fun resetJobStatus() {
        _saveResult.value = Result.success(null)
        waterMarkRepo.imageInfoList.forEach {
            it.jobState = JobState.Ready
            emitSaveProcess(it)
        }
    }

    fun clearData() {
        launch {
            waterMarkRepo.select(MediaRef.Empty)
        }
    }

    fun compressImg(activity: Activity) {
        compressedJob = viewModelScope.launch(Dispatchers.IO) {
            waterMarkFlow.value?.let {
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
            } ?: kotlin.run {
                _compressedResult.value =
                    Result.failure(
                        null,
                        code = TYPE_COMPRESS_ERROR,
                        message = "Config value is null."
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
                val image = Image(imageId, contentUri, bucketName, size, dateTaken)
                list += image
            }
            _galleryPickedImageList.value = list
            withContext(Dispatchers.Main) {
                val state = _launchScreenUiStateFlow.value.copy(
                    uiState = LaunchScreenUiState.GalleryDialog,
                    imageList = list
                )
                _launchScreenUiStateFlow.emit(state)
            }
        }
    }

    fun selectGallery(selectedList: List<Image>) {
        launch {
            withContext(Dispatchers.Default) {
                selectedList
                    .map {
                        ImageInfo(it.uri.toMediaRef())
                    }
                    .takeIf {
                        it.isNotEmpty()
                    }?.let {
                        updateImageListInternal(it)
                    }
            }
        }
    }

    fun resetGalleryData() {
        launch {
            _galleryPickedImageList.value = emptyList()
        }
    }

    fun goTemplate() {
        viewModelScope.launch {
            uiState.emit(UiState.GoTemplate)
        }
    }

    fun resetEditDialog() {
        viewModelScope.launch {
            uiState.emit(UiState.None)
        }
    }

    fun goTemplateEdit() {
        viewModelScope.launch {
            uiState.emit(UiState.GoEdit)
        }
    }

    fun useTemplate(template: Template) {
        viewModelScope.launch {
            uiState.emit(UiState.UseTemplate(template))
        }
    }

    fun goEditDialog() {
        viewModelScope.launch {
            uiState.emit(UiState.GoEditDialog)
        }
    }

    private fun onCheckedInGallery(image: Image, index: Int, checked: Boolean) {
        launch {
            withContext(Dispatchers.Default) {
                val newList = galleryPickedImageList.value?.toMutableList() ?: return@withContext
                newList[index] = image.copy(check = checked)
                _galleryPickedImageList.value = newList
                val newLaunchScreenState = launchScreenUiStateFlow.value.copy(
                    imageList = newList,
                )
                _launchScreenUiStateFlow.emit(newLaunchScreenState)
            }
        }
    }

    private fun onGalleryDismiss(selected: Boolean) {
        launch(Dispatchers.Default) {
            if (selected) {
                val newList =
                    galleryPickedImageList.value?.filter { it.check } ?: return@launch
                val imageList = newList.map {
                    ImageInfo(it.uri.toMediaRef())
                }
                updateImageListInternal(imageList)
                val nextState = launchScreenUiStateFlow.value.copy(
                    uiState = LaunchScreenUiState.Editor,
                    imageList = galleryPickedImageList.value ?: emptyList(),
                    selectedImageList = imageList,
                    waterMark = waterMarkFlow.value ?: WaterMark.default,
                    curImageInfo = imageList.firstOrNull()
                )
                withContext(Dispatchers.Main) {
                    _launchScreenUiStateFlow.emit(nextState)
                }
            } else {
                resetGalleryData()
                val newLaunchScreenState = launchScreenUiStateFlow.value.copy(
                    uiState = LaunchScreenUiState.Launch,
                    imageList = emptyList()
                )
                withContext(Dispatchers.Main) {
                    _launchScreenUiStateFlow.emit(newLaunchScreenState)
                }
            }
        }
    }

    fun onBackPressed() {
        launch {
            withContext(Dispatchers.Default) {
                when (launchScreenUiStateFlow.value.uiState) {
                    LaunchScreenUiState.Editor -> {
                        val newLaunchScreenState = launchScreenUiStateFlow.value.copy(
                            uiState = LaunchScreenUiState.Launch,
                            imageList = emptyList()
                        )
                        withContext(Dispatchers.Main) {
                            _launchScreenUiStateFlow.emit(newLaunchScreenState)
                        }
                    }

                    LaunchScreenUiState.GalleryDialog -> {
                        val newLaunchScreenState = launchScreenUiStateFlow.value.copy(
                            uiState = LaunchScreenUiState.Launch,
                            emptyList()
                        )
                        withContext(Dispatchers.Main) {
                            _launchScreenUiStateFlow.emit(newLaunchScreenState)
                        }
                    }

                    LaunchScreenUiState.Launch -> {
                        val newLaunchScreenState = launchScreenUiStateFlow.value.copy(
                            uiState = LaunchScreenUiState.Launch,
                            imageList = emptyList()
                        )
                        withContext(Dispatchers.Main) {
                            _launchScreenUiStateFlow.emit(newLaunchScreenState)
                        }
                    }
                }
            }
        }
    }

    fun process(action: Action) {
        when (action) {
            is Action.ChooseImage -> {
                launch {
                    val nextState =
                        launchScreenUiStateFlow.value.copy(uiState = LaunchScreenUiState.GalleryDialog)
                    _launchScreenUiStateFlow.emit(nextState)
                }
            }

            is Action.DialogDismiss -> {
                onGalleryDismiss(action.isSelected)
            }

            is Action.LoadImages -> {
                query(action.resolver)
            }

            is Action.GalleryImageSelected -> {
                launch {
                    onCheckedInGallery(action.image, action.index, action.isCheck)
                }
            }

            is Action.WaterMarkChange -> {
                launch {
                    onWaterMarkChanged(action.item, action.any)
                }
            }

            is Action.EditorImageSelected -> {
                launch {
                    selectImage(action.image.uri)
                }
            }

            is Action.SystemPickerImageSelected -> {
                launch(Dispatchers.Default) {
                    val newList = action.uriList

                    /**
                     * val projection = arrayOf(media-database-columns-to-retrieve)
                     * val selection = sql-where-clause-with-placeholder-variables
                     * val selectionArgs = values-of-placeholder-variables
                     * val sortOrder = sql-order-by-clause
                     *
                     * applicationContext.contentResolver.query(
                     *     MediaStore.media-type.Media.EXTERNAL_CONTENT_URI,
                     *     projection,
                     *     selection,
                     *     selectionArgs,
                     *     sortOrder
                     * )?.use { cursor ->
                     *     while (cursor.moveToNext()) {
                     *         // Use an ID column from the projection to get
                     *         // a URI representing the media item itself.
                     *     }
                     * }
                     */
                    // map the uri to image
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
                        val bucketIdColumn =
                            cursor.getColumnIndex(MediaStore.Images.Media.BUCKET_ID)
                        val bucketNameColumn =
                            cursor.getColumnIndex(MediaStore.Images.Media.BUCKET_DISPLAY_NAME)
                        val dataColumn = cursor.getColumnIndex(MediaStore.Images.Media.DATA)
                        val dateColumn =
                            cursor.getColumnIndex(if (Build.VERSION.SDK_INT > 28) MediaStore.Images.Media.DATE_MODIFIED else MediaStore.Images.Media.DATE_TAKEN)
                        val orientationColumn =
                            cursor.getColumnIndex(MediaStore.Images.Media.ORIENTATION)
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
                            val bucketName =
                                cursor.getString(bucketNameColumn) ?: ""
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
                            val image = Image(
                                imageId,
                                contentUri,
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
                    updateImageListInternal(imageInfoList)
                    val nextState = launchScreenUiStateFlow.value.copy(
                        uiState = LaunchScreenUiState.Editor,
                        imageList = imageList,
                        selectedImageList = imageInfoList,
                        waterMark = waterMarkFlow.value ?: WaterMark.default,
                        curImageInfo = imageInfoList.firstOrNull()
                    )
                    withContext(Dispatchers.Main) {
                        _launchScreenUiStateFlow.emit(nextState)
                    }
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
        const val TYPE_ERROR_NOT_IMG = "type_error_not_img"
        const val TYPE_ERROR_FILE_NOT_FOUND = "type_error_file_not_found"
        const val TYPE_ERROR_SAVE_OOM = "type_error_save_oom"
        const val TYPE_COMPRESS_ERROR = "type_CompressError"
        const val TYPE_COMPRESS_OK = "type_CompressOK"
        const val TYPE_COMPRESSING = "type_Compressing"
        const val TYPE_SAVING = "type_saving"
        const val TYPE_JOB_FINISH = "type_job_finish"
    }
}
