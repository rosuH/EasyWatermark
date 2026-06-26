package me.rosuh.easywatermark.data.repo

import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.withContext
import me.rosuh.easywatermark.MyApp
import me.rosuh.easywatermark.R
import me.rosuh.easywatermark.data.model.ImageInfo
import me.rosuh.easywatermark.data.model.MediaRef
import me.rosuh.easywatermark.data.model.TextPaintStyle
import me.rosuh.easywatermark.data.model.TextTypeface
import me.rosuh.easywatermark.data.model.WaterMark
import me.rosuh.easywatermark.data.model.WatermarkConfigRules
import me.rosuh.easywatermark.data.model.WatermarkMode
import me.rosuh.easywatermark.data.model.WatermarkTileMode
import me.rosuh.easywatermark.data.repo.WaterMarkRepository.PreferenceKeys.KEY_ALPHA
import me.rosuh.easywatermark.data.repo.WaterMarkRepository.PreferenceKeys.KEY_DEGREE
import me.rosuh.easywatermark.data.repo.WaterMarkRepository.PreferenceKeys.KEY_ENABLE_BOUNDS
import me.rosuh.easywatermark.data.repo.WaterMarkRepository.PreferenceKeys.KEY_HORIZON_GAP
import me.rosuh.easywatermark.data.repo.WaterMarkRepository.PreferenceKeys.KEY_ICON_URI
import me.rosuh.easywatermark.data.repo.WaterMarkRepository.PreferenceKeys.KEY_MODE
import me.rosuh.easywatermark.data.repo.WaterMarkRepository.PreferenceKeys.KEY_TEXT
import me.rosuh.easywatermark.data.repo.WaterMarkRepository.PreferenceKeys.KEY_TEXT_COLOR
import me.rosuh.easywatermark.data.repo.WaterMarkRepository.PreferenceKeys.KEY_TEXT_SIZE
import me.rosuh.easywatermark.data.repo.WaterMarkRepository.PreferenceKeys.KEY_TEXT_STYLE
import me.rosuh.easywatermark.data.repo.WaterMarkRepository.PreferenceKeys.KEY_TEXT_TYPEFACE
import me.rosuh.easywatermark.data.repo.WaterMarkRepository.PreferenceKeys.KEY_TILE_MODE
import me.rosuh.easywatermark.data.repo.WaterMarkRepository.PreferenceKeys.KEY_VERTICAL_GAP
import me.rosuh.easywatermark.utils.ktx.toWatermarkTileMode
import okio.IOException

class WaterMarkRepository (private val dataStore: DataStore<Preferences>) {

    private object PreferenceKeys {
        val KEY_TEXT = stringPreferencesKey(SP_KEY_TEXT)
        val KEY_TEXT_SIZE = floatPreferencesKey(SP_KEY_TEXT_SIZE)
        val KEY_TEXT_COLOR = intPreferencesKey(SP_KEY_TEXT_COLOR)
        val KEY_TEXT_STYLE = intPreferencesKey(SP_KEY_TEXT_STYLE)
        val KEY_TEXT_TYPEFACE = intPreferencesKey(SP_KEY_TEXT_TYPEFACE)
        val KEY_ALPHA = intPreferencesKey(SP_KEY_ALPHA)
        val KEY_HORIZON_GAP = intPreferencesKey(SP_KEY_HORIZON_GAP)
        val KEY_VERTICAL_GAP = intPreferencesKey(SP_KEY_VERTICAL_GAP)
        val KEY_DEGREE = floatPreferencesKey(SP_KEY_DEGREE)
        val KEY_ICON_URI = stringPreferencesKey(SP_KEY_ICON_URI)
        val KEY_MODE = intPreferencesKey(SP_KEY_WATERMARK_MODE)
        val KEY_ENABLE_BOUNDS = booleanPreferencesKey(SP_KEY_ENABLE_BOUNDS)
        val KEY_TILE_MODE = intPreferencesKey(SP_KEY_TILE_MODEL)
        val KEY_OFFSET_X = floatPreferencesKey(SP_KEY_OFFSET_X)
        val KEY_OFFSET_Y = floatPreferencesKey(SP_KEY_OFFSET_Y)
    }

    private val _selectedImage = MutableStateFlow(ImageInfo.empty())

    val selectedImage: StateFlow<ImageInfo> = _selectedImage

    val waterMark: Flow<WaterMark> = dataStore.data
        .catch { exception ->
            if (exception is IOException) {
                emit(emptyPreferences())
            } else {
                throw exception
            }
        }
        .map {
            WaterMark(
                text = it[KEY_TEXT]
                    ?: MyApp.instance.getString(R.string.config_default_water_mark_text),
                textSize = WatermarkConfigRules.clampTextSize(
                    it[KEY_TEXT_SIZE] ?: WatermarkConfigRules.DEFAULT_TEXT_SIZE
                ),
                // S4d-84: platform-neutral opaque ARGB constant equal to Color.parseColor("#FFB800")
                // (pinned by WaterMarkDefaultColorTest), removing the android.graphics.Color edge.
                textColor = it[KEY_TEXT_COLOR] ?: 0xFFFFB800.toInt(),
                textStyle = TextPaintStyle.obtainSealedClass(it[KEY_TEXT_STYLE] ?: 0),
                textTypeface = TextTypeface.obtainSealedClass(it[KEY_TEXT_TYPEFACE] ?: 0),
                alpha = it[KEY_ALPHA] ?: 255,
                degree = it[KEY_DEGREE] ?: 315f,
                hGap = it[KEY_HORIZON_GAP] ?: 0,
                vGap = it[KEY_VERTICAL_GAP] ?: 0,
                iconUri = MediaRef.parse(it[KEY_ICON_URI] ?: ""),
                markMode = WatermarkMode.fromValue(it[KEY_MODE] ?: WatermarkMode.Text.value),
                tileMode = it[KEY_TILE_MODE].toWatermarkTileMode(),
                enableBounds = it[KEY_ENABLE_BOUNDS] ?: false
            )
        }

    private val _imageMapFlow: MutableStateFlow<List<ImageInfo>> = MutableStateFlow(emptyList())
    private val imageInfoMap: MutableMap<MediaRef, Int> = mutableMapOf()

    val imageInfoMapFlow = _imageMapFlow

    val imageInfoList: List<ImageInfo>
        get() = imageInfoMapFlow.value

    suspend fun updateImageList(imageList: List<ImageInfo>) {
        val map = imageList.mapIndexed { index, imageInfo -> imageInfo.uri to index }.toMap()
        imageInfoMap.clear()
        imageInfoMap.putAll(map)
        _imageMapFlow.emit(imageList)
    }

    suspend fun updateText(text: String) {
        dataStore.edit {
            it[KEY_MODE] = WatermarkConfigRules.MODE_ON_TEXT_UPDATE.value
            it[KEY_TEXT] = text
        }
    }

    suspend fun updateTextSize(size: Float) {
        dataStore.edit { it[KEY_TEXT_SIZE] = size }
    }

    suspend fun updateColor(color: Int) {
        dataStore.edit { it[KEY_TEXT_COLOR] = color }
    }

    suspend fun updateTextStyle(style: TextPaintStyle) {
        dataStore.edit { it[KEY_TEXT_STYLE] = style.serializeKey() }
    }

    suspend fun updateTypeFace(typeface: TextTypeface) {
        dataStore.edit { it[KEY_TEXT_TYPEFACE] = typeface.serializeKey() }
    }

    suspend fun updateAlpha(alpha: Int) {
        dataStore.edit { it[KEY_ALPHA] = WatermarkConfigRules.clampAlphaByte(alpha) }
    }

    suspend fun updateHorizon(gap: Int) {
        dataStore.edit { it[KEY_HORIZON_GAP] = WatermarkConfigRules.clampHorizontalGap(gap) }
    }

    suspend fun updateVertical(gap: Int) {
        dataStore.edit {
            it[KEY_VERTICAL_GAP] = WatermarkConfigRules.clampVerticalGap(gap)
        }
    }

    suspend fun updateDegree(degree: Float) {
        dataStore.edit { it[KEY_DEGREE] = WatermarkConfigRules.clampDegree(degree) }
    }

    suspend fun updateIcon(iconUri: MediaRef) {
        dataStore.edit {
            it[KEY_MODE] = WatermarkConfigRules.MODE_ON_ICON_UPDATE.value
            it[KEY_ICON_URI] = iconUri.value
        }
    }

    suspend fun updateTileMode(mode: WatermarkTileMode) {
        dataStore.edit {
            it[KEY_TILE_MODE] = mode.storageId
        }
    }

    suspend fun updateOffset(imageInfo: ImageInfo) {
        if (imageInfo == selectedImage.value) {
            return
        }
        val index = imageInfoMap[selectedImage.value.uri] ?: kotlin.run {
            Log.e("WaterMarkRepository", "updateOffset: imageInfo not found, uri = ${selectedImage.value.uri}")
            return
        }
        val list = ArrayList(imageInfoList)
        list[index] = imageInfo
        imageInfoMap[imageInfo.uri] = index
        _imageMapFlow.emit(list)
        _selectedImage.emit(imageInfo)
    }

    suspend fun resetModeToText() {
        dataStore.edit { it[KEY_MODE] = WatermarkMode.Text.value }
    }

    suspend fun toggleBounds(enable: Boolean) {
        dataStore.edit { it[KEY_ENABLE_BOUNDS] = enable }
    }

    suspend fun resetList() {
        updateImageList(emptyList())
    }

    suspend fun select(ref: MediaRef) = withContext(Dispatchers.Default) {
        val info = imageInfoList.find { it.uri == ref } ?: ImageInfo(ref)
        _selectedImage.emit(info)
    }

    companion object {
        const val SP_NAME = "sp_water_mark_config"

        const val SP_KEY_TEXT = "${SP_NAME}_key_text"
        const val SP_KEY_TEXT_SIZE = "${SP_NAME}_key_text_size"
        const val SP_KEY_TEXT_COLOR = "${SP_NAME}_key_text_color"
        const val SP_KEY_TEXT_STYLE = "${SP_NAME}_key_text_style"
        const val SP_KEY_TEXT_TYPEFACE = "${SP_NAME}_key_text_typeface"
        const val SP_KEY_ALPHA = "${SP_NAME}_key_alpha"
        const val SP_KEY_HORIZON_GAP = "${SP_NAME}_key_horizon_gap"
        const val SP_KEY_VERTICAL_GAP = "${SP_NAME}_key_vertical_gap"
        const val SP_KEY_DEGREE = "${SP_NAME}_key_degree"
        const val SP_KEY_CHANGE_LOG = "${SP_NAME}_key_change_log"
        const val SP_KEY_ENABLE_BOUNDS = "${SP_NAME}_key_enable_bounds"
        const val SP_KEY_ICON_URI = "${SP_NAME}_key_icon_uri"
        const val SP_KEY_WATERMARK_MODE = "${SP_NAME}_key_watermark_mode"
        const val SP_KEY_IMAGE_ROTATION = "${SP_NAME}_key_watermark_mode"
        const val SP_KEY_TILE_MODEL = "${SP_NAME}_key_tile_model"
        const val SP_KEY_OFFSET_X = "${SP_NAME}_key_offset_x"
        const val SP_KEY_OFFSET_Y = "${SP_NAME}_key_offset_y"
        // Single source of truth = commonMain WatermarkConfigRules (S4d-61); these aliases stay so the
        // editor sliders (EditorScreen reads WaterMarkRepository.MAX_*) keep their public references.
        const val MAX_TEXT_SIZE = WatermarkConfigRules.MAX_TEXT_SIZE
        const val MIN_TEXT_SIZE = WatermarkConfigRules.MIN_TEXT_SIZE
        const val DEFAULT_TEXT_SIZE = WatermarkConfigRules.DEFAULT_TEXT_SIZE
        const val MAX_DEGREE = WatermarkConfigRules.MAX_DEGREE
        const val MAX_HORIZON_GAP = WatermarkConfigRules.MAX_HORIZONTAL_GAP
        const val MAX_VERTICAL_GAP = WatermarkConfigRules.MAX_VERTICAL_GAP

    }
}
