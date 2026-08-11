package me.rosuh.easywatermark.data.model

data class UserPreferences(
    val outputFormat: ImageFormat,
    val compressLevel: Int,
    /**
     * Android-only product preference: when true, Launch/add-image use the in-app MediaStore
     * Gallery (storage permission). Default false = system Photo Picker (no library permission).
     * Missing DataStore key reads as false so upgrades move to Photo Picker (P0).
     */
    val preferInAppGallery: Boolean = false,
    /**
     * ADR-0027: Content editor theme follows the currently selected photo.
     * Default **on** (missing key → true).
     */
    val followPhoto: Boolean = true,
) {
    companion object {
        val DEFAULT = UserPreferences(
            outputFormat = ImageFormat.JPEG,
            compressLevel = 80,
            preferInAppGallery = false,
            followPhoto = true,
        )
    }
}
