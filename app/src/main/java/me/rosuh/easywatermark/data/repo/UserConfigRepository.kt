package me.rosuh.easywatermark.data.repo

import android.graphics.Bitmap
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.intPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import me.rosuh.easywatermark.data.model.UserPreferences
import me.rosuh.easywatermark.data.repo.UserConfigRepository.PreferenceKeys.KEY_COMPRESS_LEVEL
import me.rosuh.easywatermark.data.repo.UserConfigRepository.PreferenceKeys.KEY_OUTPUT_FORMAT
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton


@Singleton
class UserConfigRepository @Inject constructor(
    private val dataStore: DataStore<Preferences>
) {
    private object PreferenceKeys {
        val KEY_OUTPUT_FORMAT = intPreferencesKey(SP_KEY_FORMAT)

        val KEY_COMPRESS_LEVEL = intPreferencesKey(SP_KEY_COMPRESS_LEVEL)
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
            val outputFormat = when (it[KEY_OUTPUT_FORMAT]) {
                Bitmap.CompressFormat.PNG.ordinal -> Bitmap.CompressFormat.PNG
                else -> {
                    Bitmap.CompressFormat.JPEG
                }
            }
            val compressLevel = it[KEY_COMPRESS_LEVEL] ?: DEFAULT_COMPRESS_LEVEL
            UserPreferences(outputFormat, compressLevel)
        }

    suspend fun updateFormat(
        outputFormat: Bitmap.CompressFormat
    ) {
        dataStore.edit {
            it[KEY_OUTPUT_FORMAT] = outputFormat.ordinal
        }
    }

    suspend fun updateCompressLevel(
        compressLevel: Int
    ) {
        dataStore.edit {
            it[KEY_COMPRESS_LEVEL] = compressLevel
        }
    }

    companion object {
        const val DEFAULT_COMPRESS_LEVEL = 80
        val DEFAULT_BITMAP_COMPRESS_FORMAT = Bitmap.CompressFormat.JPEG
    }
}

const val SP_NAME = "sp_water_mark_user_config"
const val SP_KEY_FORMAT = "${SP_NAME}_key_format"
const val SP_KEY_COMPRESS_LEVEL = "${SP_NAME}_key_compress_level"