package me.rosuh.easywatermark.ui

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.ContentResolver
import android.content.ContentValues
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.text.TextPaint
import android.util.Log
import android.widget.Toast
import androidx.core.content.FileProvider
import androidx.lifecycle.*
import id.zelory.compressor.Compressor
import kotlinx.coroutines.*
import me.rosuh.easywatermark.BuildConfig
import me.rosuh.easywatermark.MyApp
import me.rosuh.easywatermark.R
import me.rosuh.easywatermark.model.*
import me.rosuh.easywatermark.repo.UserConfigRepo
import me.rosuh.easywatermark.utils.FileUtils.Companion.outPutFolderName
import me.rosuh.easywatermark.utils.bitmap.decodeBitmapFromUri
import me.rosuh.easywatermark.utils.bitmap.decodeSampledBitmapFromResource
import me.rosuh.easywatermark.utils.ktx.applyConfig
import me.rosuh.easywatermark.utils.ktx.formatDate
import me.rosuh.easywatermark.widget.WaterMarkImageView
import java.io.File
import java.io.FileNotFoundException
import java.io.FileOutputStream

class MainViewModel : ViewModel() {

    val saveResult: MutableLiveData<Result<*>> = MutableLiveData()

    val compressedResult: MutableLiveData<Result<*>> = MutableLiveData()

    val config: MutableLiveData<WaterMarkConfig> by lazy {
        MutableLiveData<WaterMarkConfig>(WaterMarkConfig.pull())
    }

    val selectedImageInfoList: MutableLiveData<List<ImageInfo>> by lazy {
        MutableLiveData(emptyList())
    }

    val shareImageUri: MutableLiveData<List<ImageInfo>> = MutableLiveData()

    val saveImageUri: MutableLiveData<List<ImageInfo>> = MutableLiveData()

    private var compressedJob: Job? = null

    private val repo = UserConfigRepo
    private val userConfig: MutableLiveData<UserConfig> = repo.userConfig

    fun saveImage(contentResolver: ContentResolver, isSharing: Boolean = false) {
        viewModelScope.launch {
            if (selectedImageInfoList.value.isNullOrEmpty()) {
                saveResult.value = Result.failure(null, code = TYPE_ERROR_NOT_IMG)
                return@launch
            }
            saveResult.value =
                Result.success(null, code = if (isSharing) TYPE_SHARING else TYPE_SAVING)
            val result = generateList(contentResolver, selectedImageInfoList.value)
            if (result.isFailure()) {
                saveResult.value = Result.failure(null, code = TYPE_ERROR_FILE_NOT_FOUND)
                return@launch
            }
            if (isSharing) {
                shareImageUri.value = result.data
            } else {
                saveImageUri.value = result.data
            }
            saveResult.value = Result.success(result.data)
        }
    }

    private suspend fun generateList(
        contentResolver: ContentResolver,
        infoList: List<ImageInfo>?
    ): Result<List<ImageInfo>> =
        withContext(Dispatchers.Default) {
            if (infoList.isNullOrEmpty()) {
                return@withContext Result.failure(null, TYPE_ERROR_NOT_IMG)
            }
            infoList.forEach { info ->
                try {
                    info.result = generateImage(contentResolver, info.uri)
                } catch (fne: FileNotFoundException) {
                    fne.printStackTrace()
                    info.result = Result.failure(null, code = TYPE_ERROR_FILE_NOT_FOUND)
                } catch (oom: OutOfMemoryError) {
                    info.result = Result.failure(null, code = TYPE_ERROR_SAVE_OOM)
                }
                Log.i("generateList", "${info.uri} : ${info.result}")
            }

            return@withContext Result.success(infoList)
        }

    fun shareImage(contentResolver: ContentResolver) {
        viewModelScope.launch {
            saveImage(contentResolver, true)
        }
    }

    private suspend fun generateImage(contentResolver: ContentResolver, uri: Uri): Result<Uri> =
        withContext(Dispatchers.IO) {
            val rect = decodeBitmapFromUri(contentResolver, uri)
            if (rect.isFailure()) {
                return@withContext Result.extendMsg(rect)
            }
            val mutableBitmap = rect.data?.copy(Bitmap.Config.ARGB_8888, true)
                ?: return@withContext Result.failure(
                    null,
                    code = "-1",
                    message = "Copy bitmap from uri failed."
                )
            if (config.value == null) {
                return@withContext Result.failure(
                    null,
                    code = "-1",
                    message = "config.value == null"
                )
            }
            val canvas = Canvas(mutableBitmap)
            val tmpConfig = config.value!!
            val bitmapPaint = TextPaint().applyConfig(tmpConfig, false)
            val layoutPaint = Paint()
            layoutPaint.shader = when (config.value?.markMode) {
                WaterMarkConfig.MarkMode.Text -> {
                    WaterMarkImageView.buildTextBitmapShader(
                        config.value!!,
                        bitmapPaint,
                        Dispatchers.IO
                    )
                }
                WaterMarkConfig.MarkMode.Image -> {
                    val iconBitmapRect = decodeSampledBitmapFromResource(
                        contentResolver,
                        tmpConfig.iconUri,
                        mutableBitmap.width,
                        mutableBitmap.height
                    )
                    if (iconBitmapRect.isFailure() || iconBitmapRect.data == null) {
                        return@withContext Result.failure(
                            null,
                            code = "-1",
                            message = "decodeSampledBitmapFromResource == null"
                        )
                    }
                    val iconBitmap = iconBitmapRect.data!!
                    WaterMarkImageView.buildIconBitmapShader(
                        iconBitmap,
                        true,
                        Rect(0, 0, mutableBitmap.width, mutableBitmap.height),
                        tmpConfig,
                        bitmapPaint,
                        Dispatchers.IO
                    )
                }
                null -> return@withContext Result.failure(
                    null,
                    code = "-1",
                    message = "Unknown markmode"
                )
            }
            canvas.drawRect(
                0f, 0f,
                mutableBitmap.width.toFloat(), mutableBitmap.height.toFloat(), layoutPaint
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
                        userConfig.value?.outputFormat ?: Bitmap.CompressFormat.JPEG,
                        userConfig.value?.compressLevel ?: 95,
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
                        userConfig.value?.outputFormat ?: Bitmap.CompressFormat.JPEG,
                        userConfig.value?.compressLevel ?: 95,
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
        return if (userConfig.value?.outputFormat == Bitmap.CompressFormat.PNG) "png" else "jpg"
    }

    fun updateUri(uri: Uri) {
        config.value?.uri = uri
        forceRefresh()
    }

    fun updateUri(list: List<Uri>) {
        list.map { ImageInfo(it) }
            .takeIf {
                it.isNotEmpty()
            }
            ?.let {
                selectedImageInfoList.value = it
                config.value?.uri = it.first().uri
                forceRefresh()
            }
    }

    fun updateText(text: String) {
        config.value?.text = text
        config.value?.markMode = WaterMarkConfig.MarkMode.Text
        forceRefresh()
    }

    fun updateTextSize(textSize: Float) {
        val finalTextSize = textSize.coerceAtLeast(0f)
        config.value?.textSize = finalTextSize
        forceRefresh()
    }

    fun updateTextColor(color: Int) {
        config.value?.textColor = color
        forceRefresh()
    }

    fun updateTextStyle(style: TextPaintStyle) {
        config.value?.textStyle = style
        forceRefresh()
    }

    fun updateTextTypeface(typeface: TextTypeface) {
        config.value?.textTypeface = typeface
        forceRefresh()
    }

    fun updateAlpha(alpha: Int) {
        val finalAlpha = alpha.coerceAtLeast(0).coerceAtMost(255)
        config.value?.alpha = finalAlpha
        forceRefresh()
    }

    fun updateHorizon(gap: Int) {
        config.value?.horizonGapPercent = gap
        forceRefresh()
    }

    fun updateVertical(gap: Int) {
        config.value?.verticalGapPercent = gap
        forceRefresh()
    }

    fun updateDegree(degree: Float) {
        config.value?.degree = degree
        forceRefresh()
    }

    fun updateIcon(iconUri: Uri = config.value?.iconUri ?: Uri.parse("")) {
        config.value?.iconUri = iconUri
        viewModelScope.launch {
            if (iconUri.toString().isNotEmpty()) {
                config.value?.iconUri = iconUri
                config.value?.markMode = WaterMarkConfig.MarkMode.Image
            }
            forceRefresh()
        }
    }

    fun resetStatus() {
        saveResult.postValue(Result.success(null))
    }

    private fun forceRefresh() {
        config.value?.save()
        config.postValue(config.value)
    }

    fun compressImg(activity: Activity) {
        compressedJob = viewModelScope.launch(Dispatchers.IO) {
            config.value?.let {
                compressedResult.postValue(Result.success(null, code = TYPE_COMPRESSING))
                val tmpFile = File.createTempFile("easy_water_mark_", "_compressed")
                activity.contentResolver.openInputStream(it.uri).use { input ->
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
                    updateUri(compressedFileUri)
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
                    
                        $crashInfo
                    =====================
                    APP:
                    ${BuildConfig.VERSION_CODE}, ${BuildConfig.VERSION_NAME}, ${BuildConfig.BUILD_TYPE} 
                    Devices:
                    ${Build.VERSION.RELEASE}, ${Build.VERSION.SDK_INT}, ${Build.DEVICE}, ${Build.MODEL}, ${Build.PRODUCT}, ${Build.MANUFACTURER}
                    =====================
                    ${activity.getString(R.string.contributor_info)}
                    =====================
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

    companion object {
        const val TYPE_ERROR_NOT_IMG = "type_error_not_img"
        const val TYPE_ERROR_FILE_NOT_FOUND = "type_error_file_not_found"
        const val TYPE_ERROR_SAVE_OOM = "type_error_save_oom"
        const val TYPE_COMPRESS_ERROR = "type_CompressError"
        const val TYPE_COMPRESS_OK = "type_CompressOK"
        const val TYPE_COMPRESSING = "type_Compressing"
        const val TYPE_SHARING = "type_sharing"
        const val TYPE_SAVING = "type_saving"
    }
}
