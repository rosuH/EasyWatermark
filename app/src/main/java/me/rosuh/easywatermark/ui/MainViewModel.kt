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
import android.widget.Toast
import androidx.core.content.FileProvider
import androidx.lifecycle.*
import id.zelory.compressor.Compressor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.rosuh.easywatermark.BuildConfig
import me.rosuh.easywatermark.MyApp
import me.rosuh.easywatermark.R
import me.rosuh.easywatermark.ktx.applyConfig
import me.rosuh.easywatermark.ktx.formatDate
import me.rosuh.easywatermark.model.TextPaintStyle
import me.rosuh.easywatermark.model.TextTypeface
import me.rosuh.easywatermark.model.UserConfig
import me.rosuh.easywatermark.model.WaterMarkConfig
import me.rosuh.easywatermark.repo.UserConfigRepo
import me.rosuh.easywatermark.utils.FileUtils.Companion.outPutFolderName
import me.rosuh.easywatermark.utils.Result
import me.rosuh.easywatermark.utils.decodeBitmapFromUri
import me.rosuh.easywatermark.utils.decodeSampledBitmapFromResource
import me.rosuh.easywatermark.widget.WaterMarkImageView
import java.io.File
import java.io.FileNotFoundException
import java.io.FileOutputStream


class MainViewModel : ViewModel() {

    sealed class State(var msg: String = "") {
        object Ready : State()
        object Saving : State()
        object Sharing : State()
        object SaveOk : State()
        object ShareOk : State()
        object Compressing : State()
        object CompressOK : State()
        object CompressError : State()
        object Error : State()
        object FileNotFoundError : State()
        object OOMError : State()
    }

    sealed class TipsStatus(val values: Any? = null) {
        class None(v: Any?) : TipsStatus(values = v)
        class Alpha(v: Any?) : TipsStatus(values = v)
        class Size(v: Any?) : TipsStatus(values = v)
    }

    val result: MutableLiveData<Result<*>> = MutableLiveData()

    val saveState: MutableLiveData<State> = MutableLiveData()

    val config: MutableLiveData<WaterMarkConfig> by lazy {
        MutableLiveData<WaterMarkConfig>(WaterMarkConfig.pull())
    }

    val tipsStatus: MutableLiveData<TipsStatus> by lazy {
        MutableLiveData<TipsStatus>(TipsStatus.None(false))
    }

    val shareImageUri: MutableLiveData<Uri> = MutableLiveData()

    val saveImageUri: MutableLiveData<Uri> = MutableLiveData()

    private val repo = UserConfigRepo
    private val userConfig: MutableLiveData<UserConfig> = repo.userConfig

    fun saveImage(contentResolver: ContentResolver) {
        viewModelScope.launch {
            if (config.value?.uri?.toString().isNullOrEmpty()) {
                result.value = Result.failure(null, code = TYPE_ERROR_NOT_IMG)
                return@launch
            }
            saveState.postValue(State.Saving)
            try {
                val rect = generateImage(contentResolver, config.value?.uri ?: Uri.parse(""))
                if (rect.isFailure() || rect.data == null) {
                    result.postValue(rect)
                    return@launch
                }
                saveImageUri.value = rect.data!!
                saveState.postValue(State.SaveOk)
            } catch (fne: FileNotFoundException) {
                fne.printStackTrace()
                result.value = Result.failure(null, code = TYPE_ERROR_FILE_NOT_FOUND)
                saveState.postValue(State.FileNotFoundError)
            } catch (oom: OutOfMemoryError) {
                saveState.postValue(State.OOMError)
                result.value = Result.failure(null, code = TYPE_ERROR_SAVE_OOM)
            }
        }
    }

    fun shareImage(contentResolver: ContentResolver,) {
        viewModelScope.launch {
            if (config.value?.uri?.toString().isNullOrEmpty()) {
                result.value = Result.failure(null, code = TYPE_ERROR_NOT_IMG)
                return@launch
            }
            try {
                saveState.postValue(State.Sharing)
                val rect = generateImage(contentResolver, config.value?.uri ?: Uri.parse(""))
                if (rect.isFailure() || rect.data == null) {
                    result.postValue(rect)
                    return@launch
                }
                shareImageUri.value = rect.data!!
                saveState.postValue(State.ShareOk)
            } catch (fne: FileNotFoundException) {
                fne.printStackTrace()
                result.value = Result.failure(null, code = TYPE_ERROR_FILE_NOT_FOUND)
                saveState.postValue(State.FileNotFoundError)
            } catch (oom: OutOfMemoryError) {
                saveState.postValue(State.OOMError)
                result.value = Result.failure(null, code = TYPE_ERROR_SAVE_OOM)
            }
        }
    }

    @Throws(FileNotFoundException::class, OutOfMemoryError::class)
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
            val bitmapPaint = Paint().applyConfig(tmpConfig, false)
            val layoutPaint = Paint()
            val bounds = Rect()

            bitmapPaint.getTextBounds(tmpConfig.text, 0, tmpConfig.text.length, bounds)
            layoutPaint.shader = when (config.value?.markMode) {
                WaterMarkConfig.MarkMode.Text -> {
                    WaterMarkImageView.buildTextBitmapShader(
                        config.value!!,
                        bounds,
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
                    put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                    put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/${outPutFolderName}/")
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

    fun updateText(text: String) {
        config.value?.text = text
        config.value?.markMode = WaterMarkConfig.MarkMode.Text
        forceRefresh()
    }

    fun updateTextSize(textSize: Float) {
        val finalTextSize = textSize.coerceAtLeast(0f)
        config.value?.textSize = finalTextSize
        tipsStatus.postValue(TipsStatus.Size((finalTextSize).toInt()))
        forceRefresh()
    }

    fun updateTextSizeBy(textSize: Float) {
        val curTextSize = config.value?.textSize ?: 14f
        updateTextSize((curTextSize + textSize).coerceAtLeast(0f))
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
        tipsStatus.postValue(TipsStatus.Alpha((finalAlpha.toFloat() / 255 * 100).toInt()))
        forceRefresh()
    }

    fun updateAlphaBy(alpha: Float) {
        val curAlpha = config.value?.alpha ?: 128
        updateAlpha(((curAlpha + alpha).toInt()).coerceAtLeast(0).coerceAtMost(255))
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

    fun updateTips(tipsStatus: TipsStatus) {
        this.tipsStatus.postValue(tipsStatus)
    }

    fun resetStatus() {
        saveState.postValue(State.Ready)
    }


    private fun forceRefresh() {
        config.value?.save()
        config.postValue(config.value)
    }

    fun compressImg(activity: Activity): Job {
        return viewModelScope.launch(Dispatchers.IO) {
            config.value?.let {
                saveState.postValue(State.Compressing)
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
                    saveState.postValue(State.CompressOK)
                } catch (ie: IllegalArgumentException) {
                    saveState.postValue(State.CompressError.also { error ->
                        error.msg = "Images creates uri failed."
                    })
                }
            } ?: kotlin.run {
                saveState.postValue(State.CompressError.also { it.msg = "Config value is null." })
            }
        }
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
            putExtra(Intent.EXTRA_EMAIL, arrayOf("rosuh@qq.com"))
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

    companion object {
        const val TYPE_ERROR_NOT_IMG = "type_error_not_img"
        const val TYPE_ERROR_FILE_NOT_FOUND = "type_error_file_not_found"
        const val TYPE_ERROR_SAVE_OOM = "type_error_save_oom"
    }
}