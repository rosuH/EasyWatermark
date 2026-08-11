package me.rosuh.easywatermark.data.repo

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import me.rosuh.easywatermark.data.model.ImageFormat
import me.rosuh.easywatermark.data.model.UserPreferences
import me.rosuh.easywatermark.data.repo.UserConfigRepository.PreferenceKeys.KEY_CHANGE_LOG
import me.rosuh.easywatermark.data.repo.UserConfigRepository.PreferenceKeys.KEY_COMPRESS_LEVEL
import me.rosuh.easywatermark.data.repo.UserConfigRepository.PreferenceKeys.KEY_FOLLOW_PHOTO
import me.rosuh.easywatermark.data.repo.UserConfigRepository.PreferenceKeys.KEY_OUTPUT_FORMAT
import me.rosuh.easywatermark.data.repo.UserConfigRepository.PreferenceKeys.KEY_PREFER_IN_APP_GALLERY
import okio.IOException

class UserConfigRepository(private val dataStore: DataStore<Preferences>) {
    private object PreferenceKeys {
        val KEY_OUTPUT_FORMAT = intPreferencesKey(SP_KEY_FORMAT)

        val KEY_COMPRESS_LEVEL = intPreferencesKey(SP_KEY_COMPRESS_LEVEL)

        val KEY_PREFER_IN_APP_GALLERY = booleanPreferencesKey(SP_KEY_PREFER_IN_APP_GALLERY)

        val KEY_FOLLOW_PHOTO = booleanPreferencesKey(SP_KEY_FOLLOW_PHOTO)

        // Mirrors WaterMarkRepository.SP_KEY_CHANGE_LOG; keep persisted key byte-identical.
        val KEY_CHANGE_LOG = stringPreferencesKey("sp_water_mark_config_key_change_log")
    }

    val userPreferences: Flow<UserPreferences> = dataStore.data
        .catch { exception ->
            if (exception is IOException) {
                emit(emptyPreferences())
            } else {
                throw exception
            }
        }
        .map {
            val outputFormat = ImageFormat.fromStorageId(it[KEY_OUTPUT_FORMAT])
            val savedValue = (it[KEY_COMPRESS_LEVEL] ?: DEFAULT_COMPRESS_LEVEL).coerceAtLeast(20)
                .coerceAtMost(100)
            val compressLevel = if (savedValue % 20 != 0) DEFAULT_COMPRESS_LEVEL else savedValue
            val preferInAppGallery = it[KEY_PREFER_IN_APP_GALLERY] ?: false
            // ADR-0027 default ON when key absent.
            val followPhoto = it[KEY_FOLLOW_PHOTO] ?: true
            UserPreferences(outputFormat, compressLevel, preferInAppGallery, followPhoto)
        }

    suspend fun updateFormat(
        outputFormat: ImageFormat
    ) {
        dataStore.edit {
            it[KEY_OUTPUT_FORMAT] = outputFormat.storageId
        }
    }

    suspend fun updateCompressLevel(
        compressLevel: Int
    ) {
        dataStore.edit {
            it[KEY_COMPRESS_LEVEL] = compressLevel
        }
    }

    suspend fun updatePreferInAppGallery(preferInAppGallery: Boolean) {
        dataStore.edit {
            it[KEY_PREFER_IN_APP_GALLERY] = preferInAppGallery
        }
    }

    suspend fun updateFollowPhoto(followPhoto: Boolean) {
        dataStore.edit {
            it[KEY_FOLLOW_PHOTO] = followPhoto
        }
    }

    suspend fun saveVersionCode(versionCode: Int) {
        dataStore.edit {
            it[KEY_CHANGE_LOG] = versionCode.toString()
        }
    }

    companion object {
        const val DEFAULT_COMPRESS_LEVEL = 80

        const val SP_NAME = "sp_water_mark_user_config"
        const val SP_KEY_FORMAT = "${SP_NAME}_key_format"
        const val SP_KEY_COMPRESS_LEVEL = "${SP_NAME}_key_compress_level"
        const val SP_KEY_PREFER_IN_APP_GALLERY = "${SP_NAME}_key_prefer_in_app_gallery"
        const val SP_KEY_FOLLOW_PHOTO = "${SP_NAME}_key_follow_photo"
    }
}
