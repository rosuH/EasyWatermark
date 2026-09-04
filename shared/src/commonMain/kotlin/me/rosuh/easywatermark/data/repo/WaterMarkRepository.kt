package me.rosuh.easywatermark.data.repo

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
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
import okio.IOException

/**
 * Persisted watermark config ([waterMark]) is the durable product surface.
 *
 * **E3 residual:** in-memory image list/selection/offset ([_imageMapFlow], [_selectedImage],
 * [updateOffset], [updateImageList], [select]) remain for Session EnterEditor dual-write
 * effects and a few tests. Product editor path owns selection/offset on Session (E1);
 * do not add new production callers. Full deletion is a follow-up once Session effects
 * stop mirroring list/select into the repo.
 *
 * List/selection/offset updates are Main-confined. DataStore keys and defaults are compatibility-critical.
 */
class WaterMarkRepository(
    private val dataStore: DataStore<Preferences>,
    // the localized default watermark text is injected from the Android Koin edge
    // (RepositoryModule), removing the app-resource coupling. A provider lambda (not a precomputed
    // String) preserves the original per-emission resolution inside the flow `.map`.
    private val defaultTextProvider: () -> String,
    // the persisted-tile-id -> WatermarkTileMode read mapper is injected from the Android edge
    // (RepositoryModule passes the SDK-gated legacy mapper), so the repository no longer depends on the
    // Android `Build.VERSION`-gated extension. The injected mapper preserves the legacy behavior
    // (pre-Android-12 stored DECAL id 3 -> REPEAT), pinned by WatermarkTileModeMappingTest. NOT the pure
    // `WatermarkTileMode.fromStorageId`, which lacks the SDK gate.
    private val tileModeFromStorageId: (Int?) -> WatermarkTileMode,
    private val logError: (String) -> Unit,
) {

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
                text = it[KEY_TEXT] ?: defaultTextProvider(),
                textSize = WatermarkConfigRules.clampTextSize(
                    it[KEY_TEXT_SIZE] ?: WatermarkConfigRules.DEFAULT_TEXT_SIZE
                ),
                // platform-neutral opaque ARGB constant equal to Color.parseColor("#FFB800")
                // (pinned by WaterMarkDefaultColorTest), removing the android.graphics.Color edge.
                textColor = it[KEY_TEXT_COLOR] ?: WaterMark.default.textColor,
                textStyle = TextPaintStyle.obtainSealedClass(it[KEY_TEXT_STYLE] ?: 0),
                textTypeface = TextTypeface.obtainSealedClass(it[KEY_TEXT_TYPEFACE] ?: 0),
                alpha = it[KEY_ALPHA] ?: WaterMark.default.alpha,
                degree = it[KEY_DEGREE] ?: WaterMark.default.degree,
                hGap = it[KEY_HORIZON_GAP] ?: WaterMark.default.hGap,
                vGap = it[KEY_VERTICAL_GAP] ?: WaterMark.default.vGap,
                iconUri = MediaRef.parse(it[KEY_ICON_URI] ?: ""),
                markMode = WatermarkMode.fromValue(it[KEY_MODE] ?: WatermarkMode.Text.value),
                tileMode = tileModeFromStorageId(it[KEY_TILE_MODE]),
                enableBounds = it[KEY_ENABLE_BOUNDS] ?: false
            )
        }

    private val _imageMapFlow: MutableStateFlow<List<ImageInfo>> = MutableStateFlow(emptyList())

    val imageInfoMapFlow = _imageMapFlow

    val imageInfoList: List<ImageInfo>
        get() = imageInfoMapFlow.value

    /**
 * Replace the image list on Main. Install + selected rebind run with **no suspension** between
 * Them so they cannot interleave with [select] / [updateOffset].     */
    suspend fun updateImageList(imageList: List<ImageInfo>) {
        withContext(Dispatchers.Main.immediate) {
            // Atomic list replace (StateFlow). Offset path uses update{} only — no side MutableMap race.
            _imageMapFlow.value = imageList
            // Keep list/selected identity: if selected URI is still present, rebind to the new entry.
            _selectedImage.update { current ->
                imageList.firstOrNull { it.uri == current.uri } ?: current
            }
        }
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

    /**
 * Synchronous in-memory **offset-only** update. **Main-confined** (call from UI/Main only).
 *
 * - Does **not** mutate [imageInfo] (stale UI copies are safe to pass).
 * - List CAS via [_imageMapFlow.update] is a **pure** lambda (no outer side effects).
 * - After update, the committed object is **re-read** from the final list by URI so CAS
 * Retries cannot return a never-installed instance. * - Same offsets → returns the **existing** list entry (identity shared with list + selected).
 * - [selectedImage] is updated only when its URI still matches (atomic [MutableStateFlow.update]).
 *
 * @return the installed list entry, or null if the URI was not found (no-op).
     */
    fun updateOffset(imageInfo: ImageInfo): ImageInfo? {
        _imageMapFlow.update { current ->
            val index = current.indexOfFirst { it.uri == imageInfo.uri }
            if (index < 0) return@update current
            val existing = current[index]
            if (existing.offsetX == imageInfo.offsetX && existing.offsetY == imageInfo.offsetY) {
                return@update current
            }
            val next = existing.copy(
                offsetX = imageInfo.offsetX,
                offsetY = imageInfo.offsetY,
            )
            current.toMutableList().also { it[index] = next }
        }
        // Always re-read from the final list — never trust lambda-local objects across CAS retries.
        val committed = _imageMapFlow.value.firstOrNull { it.uri == imageInfo.uri }
        if (committed == null) {
            logError("updateOffset: imageInfo not found, uri = ${imageInfo.uri}")
            return null
        }
        _selectedImage.update { current ->
            if (current.uri == committed.uri) committed else current
        }
        return committed
    }

    suspend fun resetModeToText() {
        dataStore.edit { it[KEY_MODE] = WatermarkMode.Text.value }
    }

    /** Persist mark mode only (form Text|Icon segment); does not touch text/icon payloads. */
    suspend fun updateMarkMode(mode: WatermarkMode) {
        dataStore.edit { it[KEY_MODE] = mode.value }
    }

    suspend fun toggleBounds(enable: Boolean) {
        dataStore.edit { it[KEY_ENABLE_BOUNDS] = enable }
    }

    suspend fun resetList() {
        updateImageList(emptyList())
    }

    /**
 * Set selection from the **current** list entry for [ref] (or a temp [ImageInfo] if missing).
 * Read list + write selected happen on Main with no suspension between, so a concurrent
 * [updateOffset] on Main cannot install B_new while this still holds B_old.
     */
    suspend fun select(ref: MediaRef) = withContext(Dispatchers.Main.immediate) {
        val info = imageInfoList.find { it.uri == ref } ?: ImageInfo(ref)
        _selectedImage.value = info
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
        // Single source of truth = commonMain WatermarkConfigRules; these aliases stay so the
        // editor sliders (EditorScreen reads WaterMarkRepository.MAX_*) keep their public references.
        const val MAX_TEXT_SIZE = WatermarkConfigRules.MAX_TEXT_SIZE
        const val MIN_TEXT_SIZE = WatermarkConfigRules.MIN_TEXT_SIZE
        const val DEFAULT_TEXT_SIZE = WatermarkConfigRules.DEFAULT_TEXT_SIZE
        const val MAX_DEGREE = WatermarkConfigRules.MAX_DEGREE
        const val MAX_HORIZON_GAP = WatermarkConfigRules.MAX_HORIZONTAL_GAP
        const val MAX_VERTICAL_GAP = WatermarkConfigRules.MAX_VERTICAL_GAP

    }
}
