package me.rosuh.easywatermark.data.model

data class UserPreferences(
    val outputFormat: ImageFormat,
    val compressLevel: Int
) {
    companion object {
        val DEFAULT = UserPreferences(ImageFormat.JPEG, 80)
    }
}
