package me.rosuh.easywatermark.ui

import android.app.Activity
import android.content.ContentResolver
import android.content.ContentValues
import android.content.Intent
import android.graphics.*
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.core.content.ContextCompat
import androidx.lifecycle.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.rosuh.easywatermark.R
import me.rosuh.easywatermark.ktx.applyConfig
import me.rosuh.easywatermark.model.WaterMarkConfig
import me.rosuh.easywatermark.widget.WaterMarkImageView
import java.io.FileNotFoundException
import java.io.FileOutputStream


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

    fun saveImage(activity: MainActivity) {
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

    @Synchronized
    private suspend fun copyImage(resolver: ContentResolver, uri: Uri): Bitmap? =
        withContext(Dispatchers.IO) {
            try {
                resolver.openInputStream(uri).use {
                    return@use BitmapFactory.decodeStream(it)
                }
            } catch (fne: FileNotFoundException) {
                return@withContext null
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

            paint.getTextBounds(tmpConfig.text, 0, tmpConfig.text.length, bounds)
            paint.shader = WaterMarkImageView.buildTextBitmapShader(config.value!!, bounds, paint)
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

    fun updateIcon(activity: Activity, iconUri: Uri = config.value?.iconUri ?: Uri.parse("")) {
        config.value?.iconUri = iconUri
        viewModelScope.launch() {
            if (iconUri.toString().isNotEmpty()) {
                val iconBitmap = copyImage(activity.contentResolver, iconUri) ?: kotlin.run {
                    _saveState.postValue(State.Error.apply {
                        msg = activity.getString(R.string.error_file_not_found)
                    })
                    return@launch
                }
//                val iconBitmap = drawableToBitmap(ContextCompat.getDrawable(activity, R.drawable.ic_github)!!) ?: kotlin.run {
//                    _saveState.postValue(State.Error.apply {
//                        msg = activity.getString(R.string.error_file_not_found)
//                    })
//                    return@launch
//                }
                if (config.value?.iconBitmap?.isRecycled != true) {
                    config.value?.iconBitmap?.recycle()
                }
                config.value?.iconBitmap = iconBitmap
            }
            _config.forceRefresh()
        }
    }

    fun updateMarkMode(mode: WaterMarkConfig.MarkMode) {
        config.value?.markMode = mode
        _config.forceRefresh()
    }

    private suspend fun drawableToBitmap(drawable: Drawable): Bitmap? =
        withContext(Dispatchers.IO) {
            if (drawable is BitmapDrawable) {
                return@withContext drawable.bitmap
            }
            val bitmap = Bitmap.createBitmap(
                drawable.intrinsicWidth,
                drawable.intrinsicHeight,
                Bitmap.Config.ARGB_8888
            )
            val canvas = Canvas(bitmap)
            drawable.setBounds(0, 0, canvas.width, canvas.height)
            drawable.draw(canvas)
            return@withContext bitmap
        }

    private fun <T> MutableLiveData<T>.forceRefresh() {
        this.postValue(this.value)
    }
}