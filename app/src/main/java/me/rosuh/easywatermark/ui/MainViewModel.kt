package me.rosuh.easywatermark.ui

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.ContentResolver
import android.content.ContentUris
import android.content.ContentValues
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Shader
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.text.TextPaint
import android.util.Log
import android.widget.Toast
import androidx.core.content.FileProvider
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.asLiveData
import androidx.lifecycle.map
import androidx.lifecycle.viewModelScope
import androidx.palette.graphics.Palette
import dagger.hilt.android.lifecycle.HiltViewModel
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
import me.rosuh.easywatermark.data.model.ImageInfo
import me.rosuh.easywatermark.data.model.JobState
import me.rosuh.easywatermark.data.model.Result
import me.rosuh.easywatermark.data.model.TextPaintStyle
import me.rosuh.easywatermark.data.model.TextTypeface
import me.rosuh.easywatermark.data.model.UserPreferences
import me.rosuh.easywatermark.data.model.ViewInfo
import me.rosuh.easywatermark.data.model.WaterMark
import me.rosuh.easywatermark.data.model.entity.Template
import me.rosuh.easywatermark.data.repo.MemorySettingRepo
import me.rosuh.easywatermark.data.repo.TemplateRepository
import me.rosuh.easywatermark.data.repo.UserConfigRepository
import me.rosuh.easywatermark.data.repo.WaterMarkRepository
import me.rosuh.easywatermark.ui.widget.WaterMarkImageView
import me.rosuh.easywatermark.utils.FileUtils.Companion.outPutFolderName
import me.rosuh.easywatermark.utils.bitmap.calculateInSampleSize
import me.rosuh.easywatermark.utils.bitmap.decodeBitmapFromUri
import me.rosuh.easywatermark.utils.bitmap.decodeSampledBitmapFromResource
import me.rosuh.easywatermark.utils.ktx.applyConfig
import me.rosuh.easywatermark.utils.ktx.formatDate
import me.rosuh.easywatermark.utils.ktx.launch
import java.io.File
import java.io.FileNotFoundException
import java.io.FileOutputStream
import java.util.Date
import javax.inject.Inject

@HiltViewModel
class MainViewModel @Inject constructor(
    private val userRepo: UserConfigRepository,
    private val waterMarkRepo: WaterMarkRepository,
    private val memorySettingRepo: MemorySettingRepo,
    private val templateRepo: TemplateRepository,
) : ViewModel() {

    var nextSelectedPos: Int = 0

    val saveResult: MutableLiveData<Result<*>> = MutableLiveData()

    val compressedResult: MutableLiveData<Result<*>> = MutableLiveData()

    val waterMark: LiveData<WaterMark> = waterMarkRepo.waterMark.asLiveData()

    private val uiState: MutableStateFlow<UiState> = MutableStateFlow(UiState.None)

    val uiStateFlow: StateFlow<UiState> = uiState.asStateFlow()

    private var autoScroll = true

    val imageList: LiveData<Pair<List<ImageInfo>, Boolean>> =
        waterMarkRepo.imageInfoMapFlow.asLiveData().map { Pair(it, autoScroll) }

    val galleryPickedImageList: MutableLiveData<List<Image>> = MutableLiveData()

    val selectedImage: LiveData<ImageInfo> = waterMarkRepo.selectedImage.asLiveData()

    private val saveImageUri: MutableLiveData<List<ImageInfo>> = MutableLiveData()

    val saveProcess: MutableLiveData<ImageInfo?> = MutableLiveData()

    private var compressedJob: Job? = null

    private var userPreferences: StateFlow<UserPreferences> = userRepo.userPreferences.stateIn(
        viewModelScope,
        SharingStarted.Eagerly,
        UserPreferences.DEFAULT
    )

    val outputFormat: Bitmap.CompressFormat
        get() = userPreferences.value.outputFormat

    val compressLevel: Int
        get() = userPreferences.value.compressLevel

    val colorPalette: MutableLiveData<Palette> = MutableLiveData()

    private var matrixValues = FloatArray(9)

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
                creationDate = Date(),
                lastModifiedDate = Date()
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
        viewInfo: ViewInfo,
        imageList: List<ImageInfo>
    ) {
        viewModelScope.launch {
            if (this@MainViewModel.imageList.value?.first.isNullOrEmpty()) {
                saveResult.value = Result.failure(null, code = TYPE_ERROR_NOT_IMG)
                return@launch
            }
            saveResult.value =
                Result.success(null, code = TYPE_SAVING)
            val result = generateList(contentResolver, viewInfo, imageList)
            if (result.isFailure()) {
                saveResult.value = Result.failure(null, code = TYPE_ERROR_FILE_NOT_FOUND)
                return@launch
            }
            saveImageUri.value = result.data!!
            saveResult.value = Result.success(code = TYPE_JOB_FINISH, data = result.data)
        }
    }

    private suspend fun generateList(
        contentResolver: ContentResolver,
        viewInfo: ViewInfo,
        infoList: List<ImageInfo>?
    ): Result<List<ImageInfo>> =
        withContext(Dispatchers.Default) {
            if (infoList.isNullOrEmpty()) {
                return@withContext Result.failure(null, TYPE_ERROR_NOT_IMG)
            }
            infoList.forEach { info ->
                try {
                    info.jobState = JobState.Ing
                    launch(Dispatchers.Main) { saveProcess.value = info }
                    info.result = generateImage(contentResolver, viewInfo, info)
                    info.jobState = JobState.Success(info.result!!)
                    launch(Dispatchers.Main) { saveProcess.value = info }
                } catch (fne: FileNotFoundException) {
                    fne.printStackTrace()
                    info.result = Result.failure(null, code = TYPE_ERROR_FILE_NOT_FOUND)
                    info.jobState = JobState.Failure(info.result!!)
                    saveProcess.postValue(info)
                } catch (oom: OutOfMemoryError) {
                    info.result = Result.failure(null, code = TYPE_ERROR_SAVE_OOM)
                    info.jobState = JobState.Failure(info.result!!)
                    saveProcess.postValue(info)
                }
                Log.i("generateList", "${info.uri} : ${info.result}")
            }
            // reset process state
            saveProcess.postValue(null)
            return@withContext Result.success(infoList)
        }

    private suspend fun generateImage(
        contentResolver: ContentResolver,
        viewInfo: ViewInfo,
        imageInfo: ImageInfo
    ): Result<Uri> =
        withContext(Dispatchers.IO) {
            val rect = decodeBitmapFromUri(contentResolver, imageInfo.uri)
            if (rect.isFailure()) {
                return@withContext Result.extendMsg(rect)
            }
            val mutableBitmap = rect.data?.bitmap?.copy(Bitmap.Config.ARGB_8888, true)
                ?: return@withContext Result.failure(
                    null,
                    code = "-1",
                    message = "Copy bitmap from uri failed."
                )

            val inSample = calculateInSampleSize(
                mutableBitmap.width,
                mutableBitmap.height,
                WaterMarkImageView.calculateDrawLimitWidth(viewInfo.width, viewInfo.paddingLeft),
                WaterMarkImageView.calculateDrawLimitHeight(viewInfo.height, viewInfo.paddingRight),
            )
            imageInfo.width = mutableBitmap.width
            imageInfo.height = mutableBitmap.height
            val tmpConfig = waterMark.value ?: return@withContext Result.failure(
                null,
                code = "-1",
                message = "config.value == null"
            )
            imageInfo.inSample = inSample
            val canvas = Canvas(mutableBitmap)
            // generate matrix of drawable
            val imageMatrix = WaterMarkImageView.adjustMatrix(
                Matrix(),
                viewInfo.width,
                viewInfo.height,
                viewInfo.paddingLeft,
                viewInfo.paddingTop,
                imageInfo.width,
                imageInfo.height
            )
            Log.i(
                "generateImage",
                """
                    imageMatrix = $imageMatrix,
                    inSample = $inSample,
                    imageInfo = $imageInfo
                    viewInfo = $viewInfo,
                    bitmapW = ${mutableBitmap.width}
                    bitmapH = ${mutableBitmap.height},
                """.trimIndent()
            )
            // calculate the scale factor
            imageMatrix.getValues(matrixValues)
            imageInfo.scaleX = 1 / matrixValues[Matrix.MSCALE_X]
            imageInfo.scaleY = 1 / matrixValues[Matrix.MSCALE_X]
            val bitmapPaint = TextPaint().applyConfig(imageInfo, tmpConfig, isScale = false)
            val layoutPaint = Paint()
            val shader = when (waterMark.value?.markMode) {
                WaterMarkRepository.MarkMode.Text -> {
                    WaterMarkImageView.buildTextBitmapShader(
                        imageInfo,
                        waterMark.value!!,
                        bitmapPaint,
                        Dispatchers.IO
                    )
                }
                WaterMarkRepository.MarkMode.Image -> {
                    val iconBitmapRect = decodeSampledBitmapFromResource(
                        contentResolver,
                        tmpConfig.iconUri,
                        viewInfo.width,
                        viewInfo.height
                    )
                    if (iconBitmapRect.isFailure() || iconBitmapRect.data == null) {
                        return@withContext Result.failure(
                            null,
                            code = "-1",
                            message = "decodeSampledBitmapFromResource == null"
                        )
                    }
                    val iconBitmap = iconBitmapRect.data!!.bitmap
                    WaterMarkImageView.buildIconBitmapShader(
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

            layoutPaint.shader = shader?.bitmapShader

            if (tmpConfig.obtainTileMode() == Shader.TileMode.CLAMP) {
                canvas.translate(
                    0 + imageInfo.offsetX * mutableBitmap.width,
                    0 + imageInfo.offsetY * mutableBitmap.height
                )
                canvas.drawRect(
                    0f,
                    0f,
                    (shader?.width ?: 0).toFloat(),
                    (shader?.height ?: 0).toFloat(),
                    layoutPaint
                )
            } else {
                canvas.drawRect(
                    0f,
                    0f,
                    mutableBitmap.width.toFloat(),
                    mutableBitmap.height.toFloat(),
                    layoutPaint
                )
            }

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
                        outputFormat,
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
                        outputFormat,
                        compressLevel,
                        fileOutputStream
                    )
                }
                val outputUri = FileProvider.getUriForFile(
                    MyApp.instance,
                    "${BuildConfig.APPLICATION_ID}.fileprovider",
                    outputFile
                )
                MyApp.instance.sendBroadcast(
                    Intent(
                        Intent.ACTION_MEDIA_SCANNER_SCAN_FILE,
                        Uri.fromFile(outputFile)
                    )
                )
                Result.success(outputUri)
            }
        }

    private fun generateOutputName(): String {
        return "ewm_${System.currentTimeMillis()}.${trapOutputExtension()}"
    }

    private fun trapOutputExtension(): String {
        return if (outputFormat == Bitmap.CompressFormat.PNG) "png" else "jpg"
    }

    fun selectImage(uri: Uri) {
        if (selectedImage.value?.uri == uri) {
            return
        }
        launch {
            waterMarkRepo.select(uri)
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
            autoScroll = true
            waterMarkRepo.select(list.first().uri)
            nextSelectedPos = 0
            waterMarkRepo.updateImageList(list)
        }
    }

    private suspend fun generateImageInfoList(list: List<Uri>) =
        withContext(Dispatchers.Default) {
            return@withContext list.toSet()
                .map { ImageInfo(it) }
                .takeIf {
                    it.isNotEmpty()
                }
        }

    fun updateText(text: String) {
        launch {
            waterMarkRepo.updateText(text)
        }
    }

    fun updateTextSize(textSize: Float) {
        launch {
            val finalTextSize = textSize.coerceAtLeast(0f)
            waterMarkRepo.updateTextSize(finalTextSize)
        }
    }

    fun updateTextColor(color: Int) {
        launch {
            waterMarkRepo.updateColor(color)
        }
    }

    fun updateTextStyle(style: TextPaintStyle) {
        launch {
            waterMarkRepo.updateTextStyle(style)
        }
    }

    fun updateTextTypeface(typeface: TextTypeface) {
        launch {
            waterMarkRepo.updateTypeFace(typeface)
        }
    }

    fun updateAlpha(alpha: Int) {
        launch {
            val finalAlpha = alpha.coerceAtLeast(0).coerceAtMost(255)
            waterMarkRepo.updateAlpha(finalAlpha)
        }
    }

    fun updateHorizon(gap: Int) {
        launch {
            waterMarkRepo.updateHorizon(gap)
        }
    }

    fun updateVertical(gap: Int) {
        launch {
            waterMarkRepo.updateVertical(gap)
        }
    }

    fun updateDegree(degree: Float) {
        launch {
            waterMarkRepo.updateDegree(degree)
        }
    }

    fun updateIcon(iconUri: Uri) {
        launch {
            if (iconUri.toString().isNotEmpty()) {
                waterMarkRepo.updateIcon(iconUri)
            }
        }
    }

    fun updateTileMode(tileMode: Shader.TileMode) {
        launch {
            autoScroll = false
            waterMarkRepo.updateTileMode(tileMode)
        }
    }

    fun updateOffset(info: ImageInfo) {
        launch {
            autoScroll = false
            waterMarkRepo.updateOffset(info)
        }
    }

    fun saveOutput(format: Bitmap.CompressFormat, level: Int) {
        viewModelScope.launch {
            userRepo.updateFormat(format)
            userRepo.updateCompressLevel(level)
        }
        resetJobStatus()
    }

    fun removeImage(
        imageInfo: ImageInfo?,
        curSelectedPos: Int
    ) {
        val list = imageList.value?.first?.toMutableList() ?: return
        val removePos = list.indexOf(imageInfo)
        list.removeAt(removePos)
        val selectedPos =
            if (removePos < curSelectedPos || removePos >= (imageList.value?.first?.size
                    ?: 0) - 1
            ) {
                (curSelectedPos - 1).coerceAtLeast(0)
            } else {
                curSelectedPos
            }
        launch {
            autoScroll = false
            nextSelectedPos = selectedPos
            waterMarkRepo.updateImageList(list)
            if (removePos == curSelectedPos) {
                list.getOrNull(selectedPos)?.uri?.let { selectImage(it) }
            }
        }
    }

    fun updateColorPalette(palette: Palette) {
        colorPalette.postValue(palette)
        memorySettingRepo.updatePalette(palette)
    }

    fun resetJobStatus() {
        saveResult.postValue(Result.success(null))
        imageList.value?.first?.forEach {
            it.jobState = JobState.Ready
            saveProcess.value = it
        }
    }

    fun clearData() {
        launch {
            waterMarkRepo.select(Uri.EMPTY)
        }
    }

    fun compressImg(activity: Activity) {
        compressedJob = viewModelScope.launch(Dispatchers.IO) {
            waterMark.value?.let {
                compressedResult.postValue(Result.success(null, code = TYPE_COMPRESSING))
                val tmpFile = File.createTempFile("easy_water_mark_", "_compressed")
                activity.contentResolver.openInputStream(waterMarkRepo.imageInfoList.first().uri)
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
                    selectImage(compressedFileUri)
                    compressedResult.postValue(Result.success(null, code = TYPE_COMPRESS_OK))
                } catch (ie: IllegalArgumentException) {
                    compressedResult.postValue(
                        Result.failure(
                            null,
                            code = TYPE_COMPRESS_ERROR,
                            message = "Images creates uri failed."
                        )
                    )
                }
            } ?: kotlin.run {
                compressedResult.postValue(
                    Result.failure(
                        null,
                        code = TYPE_COMPRESS_ERROR,
                        message = "Config value is null."
                    )
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
        launch { userRepo.saveVersionCode() }
    }

    fun query(contentResolver: ContentResolver) {
        launch {
            queryInternal(contentResolver)
        }
    }

    private suspend fun queryInternal(
        contentResolver: ContentResolver,
        force: Boolean = galleryPickedImageList.value == null
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
            galleryPickedImageList.postValue(list)
        }
    }

    fun selectGallery(selectedList: List<Image>) {
        launch {
            withContext(Dispatchers.Default) {
                selectedList
                    .map {
                        ImageInfo(it.uri)
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
            withContext(Dispatchers.Default) {
                val iterator = galleryPickedImageList.value?.iterator() ?: return@withContext
                while (iterator.hasNext()) {
                    val image = iterator.next()
                    image.check = false
                }
            }
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
