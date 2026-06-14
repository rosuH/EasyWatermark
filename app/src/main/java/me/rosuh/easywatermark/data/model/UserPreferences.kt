package me.rosuh.easywatermark.data.model

import androidx.annotation.Keep
import me.rosuh.easywatermark.data.repo.UserConfigRepository

@Keep
data class UserPreferences(
    val outputFormat: ImageFormat,
    val compressLevel: Int
) {
    companion object {
        val DEFAULT = UserPreferences(
            UserConfigRepository.DEFAULT_OUTPUT_FORMAT,
            UserConfigRepository.DEFAULT_COMPRESS_LEVEL
        )
    }
}
