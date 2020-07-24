package me.rosuh.easywatermark

import android.app.Activity
import android.content.ContentResolver
import android.content.ContentValues
import android.content.Intent
import android.graphics.*
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.lifecycle.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.rosuh.easywatermark.config.WaterMarkConfig
import me.rosuh.easywatermark.config.WaterMarkConfig.Companion.calculateCircleRadius
import me.rosuh.easywatermark.ktx.applyConfig
import java.io.FileOutputStream
import kotlin.math.roundToInt

class MainViewModel : ViewModel() {

    sealed class State(var msg: String = "") {
        object Ready : State()
        object Saving : State()
        object SaveOk : State()
        object ShareOk : State()
        object Error : State()
    }

    private val _saveState: MutableLiveData<State> = MutableLiveData()

    val saveState: LiveData<State> = Transformations.map(_saveState) { it }

    private val _config: MutableLiveData<WaterMarkConfig> = MutableLiveData(WaterMarkConfig())

    val config: LiveData<WaterMarkConfig> = Transformations.map(_config) {
        it.apply {
            save()
        }
    }

    fun saveImage(resolver: ContentResolver) {
        viewModelScope.launch {
            _saveState.postValue(State.Saving)
            val uri = generateImage(resolver, config.value?.uri ?: Uri.parse(""))
            if (uri?.toString().isNullOrEmpty()) {
                _saveState.postValue(State.Error.apply { msg = "Uri is empty" })
                return@launch
            }
            _saveState.postValue(State.SaveOk)
        }
    }

    fun shareImage(activity: Activity) {
        viewModelScope.launch {
            _saveState.postValue(State.Saving)
            val uri = generateImage(activity.contentResolver, config.value?.uri ?: Uri.parse(""))
            if (uri?.toString().isNullOrEmpty()) {
                _saveState.postValue(State.Error.apply { msg = "Uri is empty" })
                return@launch
            }
            val intent = Intent(Intent.ACTION_SEND)
            intent.type = "image/jpeg"
            intent.putExtra(Intent.EXTRA_STREAM, uri)
            activity.startActivity(
                Intent.createChooser(
                    intent,
                    activity.getString(R.string.tips_share_image)
                )
            )
            _saveState.postValue(State.ShareOk)
        }
    }

    @Synchronized
    private suspend fun copyImage(resolver: ContentResolver, uri: Uri): Bitmap? =
        withContext(Dispatchers.IO) {
            resolver.openInputStream(uri).use {
                return@use BitmapFactory.decodeStream(it)
            }
        }

    private suspend fun generateImage(resolver: ContentResolver, uri: Uri): Uri? =
        withContext(Dispatchers.IO) {
            val mutableBitmap =
                copyImage(resolver, uri)?.copy(Bitmap.Config.ARGB_8888, true)
                    ?: return@withContext null
            if (config.value == null) {
                return@withContext null
            }
            val canvas = Canvas(mutableBitmap)
            val tmpConfig = config.value!!
            val paint = Paint().applyConfig(tmpConfig)
            val bounds = Rect()
            canvas.save()
            canvas.rotate(
                tmpConfig.degree,
                (mutableBitmap.width / 2).toFloat(),
                (mutableBitmap.height / 2).toFloat()
            )
            paint.getTextBounds(tmpConfig.text, 0, tmpConfig.text.length, bounds)
            val textWidth = bounds.width().toFloat()
            val textHeight = bounds.height().toFloat()
            val horizonCount =
                (calculateCircleRadius(
                    mutableBitmap.width,
                    mutableBitmap.height
                ) / (textWidth + tmpConfig.calculateHorizon(mutableBitmap.width))).roundToInt()
            val verticalCount =
                (calculateCircleRadius(
                    mutableBitmap.width,
                    mutableBitmap.height
                ) / (textHeight + tmpConfig.calculateHorizon(mutableBitmap.height))).roundToInt()
            for (iX in 0..horizonCount) {
                for (iY in 0..verticalCount) {
                    canvas.drawText(
                        tmpConfig.text,
                        iX * (textWidth + (if (iX == 0) 0 else tmpConfig.calculateHorizon(
                            mutableBitmap.width
                        ))),
                        iY * (textHeight + (if (iY == 0) 0 else tmpConfig.calculateHorizon(
                            mutableBitmap.height
                        ))),
                        paint
                    )
                }

            }
            canvas.restore()

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

            return@withContext imageContentUri
        }

    fun updateUri(uri: Uri) {
        config.value?.uri = uri
        _config.forceRefresh()
    }

    fun updateText(text: String) {
        config.value?.text = text
        _config.forceRefresh()
    }

    fun updateTextSize(textSize: Float) {
        config.value?.textSize = textSize
        _config.forceRefresh()
    }

    fun updateTextColor(color: Int) {
        config.value?.textColor = color
        _config.forceRefresh()
    }

    fun updateTextStyle(style: Paint.Style) {
        config.value?.textStyle = style
        _config.forceRefresh()
    }

    fun updateAlpha(alpha: Int) {
        config.value?.alpha = alpha
        _config.forceRefresh()
    }

    fun updateHorizon(gap: Int) {
        config.value?.horizonGapPercent = gap
        _config.forceRefresh()
    }

    fun updateVertical(gap: Int) {
        config.value?.verticalGapPercent = gap
        _config.forceRefresh()
    }

    fun updateDegree(degree: Float) {
        config.value?.degree = degree
        _config.forceRefresh()
    }

    private fun <T> MutableLiveData<T>.forceRefresh() {
        this.value = this.value
    }
}