package me.rosuh.easywatermark.model

import android.content.SharedPreferences
import android.graphics.Bitmap
import androidx.core.content.edit
import me.rosuh.easywatermark.MyApp

class UserConfig private constructor(
) {

    var outputFormat: Bitmap.CompressFormat = Bitmap.CompressFormat.JPEG

    var compressLevel: Int = 95

    private fun restore(sp: SharedPreferences = MyApp.userConfigSp()) {
        with(sp) {
            outputFormat = when (getInt(SP_KEY_FORMAT, Bitmap.CompressFormat.JPEG.ordinal)) {
                Bitmap.CompressFormat.PNG.ordinal -> Bitmap.CompressFormat.PNG
                else -> {
                    Bitmap.CompressFormat.JPEG
                }
            }
            compressLevel = getInt(SP_KEY_COMPRESS_LEVEL, 95)
        }
    }

    fun save(sp: SharedPreferences = MyApp.userConfigSp()) {
        sp.edit {
            putInt(SP_KEY_FORMAT, outputFormat.ordinal)
            putInt(SP_KEY_COMPRESS_LEVEL, compressLevel)
        }
    }

    companion object {
        const val SP_NAME = "sp_water_mark_user_config"

        const val SP_KEY_FORMAT = "${SP_NAME}_key_format"
        const val SP_KEY_COMPRESS_LEVEL = "${SP_NAME}_key_compress_level"

        fun pull(
            sp: SharedPreferences = MyApp.userConfigSp()
        ): UserConfig {
            return UserConfig().apply {
                restore(sp)
            }
        }
    }
}
