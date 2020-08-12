package me.rosuh.easywatermark.ui

import android.Manifest
import android.app.Activity
import android.content.ContentResolver
import android.content.ContentValues
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.rosuh.easywatermark.R
import me.rosuh.easywatermark.ktx.applyConfig
import me.rosuh.easywatermark.model.WaterMarkConfig
import me.rosuh.easywatermark.utils.decodeBitmapFromUri
import me.rosuh.easywatermark.utils.decodeSampledBitmapFromResource
import me.rosuh.easywatermark.widget.WaterMarkImageView
import java.io.FileOutputStream


class MainViewModel : ViewModel() {

    sealed class State(var msg: String = "") {
        object Ready : State()
        object Saving : State()
        object Sharing : State()
        object SaveOk : State()
        object ShareOk : State()
        object Error : State()
    }

    private val _saveState: MutableLiveData<State> = MutableLiveData()

    val saveState: LiveData<State> = Transformations.map(_saveState) { it }

    val config: MutableLiveData<WaterMarkConfig> by lazy {
        MutableLiveData<WaterMarkConfig>(WaterMarkConfig())
    }

    fun isPermissionGrated(activity: Activity) =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q || ContextCompat.checkSelfPermission(
            activity,
            Manifest.permission.READ_EXTERNAL_STORAGE
        ) == PackageManager.PERMISSION_GRANTED

    /**
     * 申请权限
     */
    fun requestPermission(activity: Activity) {
        ActivityCompat.requestPermissions(
            activity,
            arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE),
            MainActivity.WRITE_PERMISSION_REQUEST_CODE
        )
    }

    fun saveImage(activity: Activity) {
        if (!isPermissionGrated(activity)) {
            requestPermission(activity)
            return
        }
        viewModelScope.launch {
            if (config.value?.uri?.toString().isNullOrEmpty()) {
                _saveState.postValue(State.Error.apply {
                    msg = activity.getString(R.string.error_not_img)
                })
                return@launch
            }
            _saveState.postValue(State.Saving)
            val outputUri =
                generateImage(activity.contentResolver, config.value?.uri ?: Uri.parse(""))
            if (outputUri?.toString().isNullOrEmpty()) {
                _saveState.postValue(State.Error.apply {
                    msg = activity.getString(R.string.error_file_not_found)
                })
                return@launch
            }
            _saveState.postValue(State.SaveOk)
        }
    }

    fun shareImage(activity: Activity) {
        if (!isPermissionGrated(activity)) {
            requestPermission(activity)
            return
        }
        viewModelScope.launch {
            if (config.value?.uri?.toString().isNullOrEmpty()) {
                _saveState.postValue(State.Error.apply {
                    msg = activity.getString(R.string.error_not_img)
                })
                return@launch
            }
            _saveState.postValue(State.Sharing)
            delay(1500)
            val outputUri =
                generateImage(activity.contentResolver, config.value?.uri ?: Uri.parse(""))
            if (outputUri?.toString().isNullOrEmpty()) {
                _saveState.postValue(State.Error.apply {
                    msg = activity.getString(R.string.error_file_not_found)
                })
                return@launch
            }
            val intent = Intent(Intent.ACTION_SEND)
            intent.type = "image/jpeg"
            intent.putExtra(Intent.EXTRA_STREAM, outputUri)
            activity.startActivity(
                Intent.createChooser(
                    intent,
                    activity.getString(R.string.tips_share_image)
                )
            )
            _saveState.postValue(State.ShareOk)
        }
    }

    private suspend fun generateImage(resolver: ContentResolver, uri: Uri): Uri? =
        withContext(Dispatchers.IO) {
            val mutableBitmap =
                decodeBitmapFromUri(resolver, uri)?.copy(Bitmap.Config.ARGB_8888, true)
                    ?: return@withContext null
            if (config.value == null) {
                return@withContext null
            }
            val canvas = Canvas(mutableBitmap)
            val tmpConfig = config.value!!
            val paint = Paint().applyConfig(tmpConfig)
            val bounds = Rect()

            paint.getTextBounds(tmpConfig.text, 0, tmpConfig.text.length, bounds)
            paint.shader = when (config.value?.markMode) {
                WaterMarkConfig.MarkMode.Text -> {
                    WaterMarkImageView.buildTextBitmapShader(
                        config.value!!,
                        bounds,
                        paint,
                        Dispatchers.IO
                    )
                }
                WaterMarkConfig.MarkMode.Image -> {
                    val iconBitmap = decodeSampledBitmapFromResource(
                        resolver,
                        tmpConfig.iconUri,
                        mutableBitmap.width,
                        mutableBitmap.height
                    ) ?: return@withContext null
                    WaterMarkImageView.buildIconBitmapShader(
                        iconBitmap,
                        true,
                        Rect(0, 0, mutableBitmap.width, mutableBitmap.height),
                        tmpConfig,
                        paint,
                        Dispatchers.IO
                    )
                }
                null -> return@withContext null
            }
            canvas.drawRect(
                0f, 0f,
                mutableBitmap.width.toFloat(), mutableBitmap.height.toFloat(), paint
            )

            return@withContext if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val imageCollection =
                    MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
                val imageDetail = ContentValues().apply {
                    put(
                        MediaStore.Images.Media.DISPLAY_NAME,
                        "Easy_water_mark_${System.currentTimeMillis()}.jpg"
                    )
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        put(MediaStore.Images.Media.IS_PENDING, 1)
                    }
                }

                val imageContentUri = resolver.insert(imageCollection, imageDetail)
                resolver.openFileDescriptor(imageContentUri!!, "w", null).use { pfd ->
                    mutableBitmap.compress(
                        Bitmap.CompressFormat.PNG,
                        100,
                        FileOutputStream(pfd!!.fileDescriptor)
                    )
                }
                imageDetail.clear()
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    imageDetail.put(MediaStore.Images.Media.IS_PENDING, 0)
                }
                resolver.update(imageContentUri, imageDetail, null, null)
                imageContentUri
            } else {
                // need request write_storage permission
                val u = MediaStore.Images.Media.insertImage(
                    resolver,
                    mutableBitmap,
                    "Easy_water_mark_${System.currentTimeMillis()}.jpg",
                    ""
                )
                Uri.parse(u)
            }
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
        config.value?.textSize = textSize.coerceAtLeast(0f)
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

    fun updateTextStyle(style: Paint.Style) {
        config.value?.textStyle = style
        forceRefresh()
    }

    fun updateAlpha(alpha: Int) {
        config.value?.alpha = alpha.coerceAtLeast(0).coerceAtMost(255)
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

    fun updateIcon(activity: Activity, iconUri: Uri = config.value?.iconUri ?: Uri.parse("")) {
        config.value?.iconUri = iconUri
        viewModelScope.launch {
            if (iconUri.toString().isNotEmpty()) {
                config.value?.iconUri = iconUri
                config.value?.markMode = WaterMarkConfig.MarkMode.Image
            }
            forceRefresh()
        }
    }


    private fun forceRefresh() {
        config.value?.save()
        config.postValue(config.value)
    }
}