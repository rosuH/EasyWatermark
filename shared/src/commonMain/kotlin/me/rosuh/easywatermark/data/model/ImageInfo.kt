package me.rosuh.easywatermark.data.model

data class ImageInfo(
    // platform-neutral image identity (ADR-0007). Android `Uri` is converted at the edges
    // (picker/share-in/gallery construction, decode/Coil/save) via `MediaRefExt`.
    val uri: MediaRef,
    var width: Int = 1,
    var height: Int = 1,
    var inSample: Int = 1,
    var scaleX: Float = 1f,
    var scaleY: Float = 1f,
    var result: Result<*>? = null,
    var jobState: JobState = JobState.Ready,
    var isInDelModel: Boolean = false,
    // offsetX/offsetY are the normalized watermark offset; expected range 0f..1f (documented
    // invariant, unenforced). An Android-only doc/lint range annotation was dropped on the move to
    // commonMain (it had no runtime effect).
    val offsetX: Float = 0.5f,
    val offsetY: Float = 0.5f,
) {
    // Export success payload lives in [result] (typically `Result<MediaRef>` on Android). Share /
    // open-gallery map `result.data` at the Android edge (`uriFromExportResultData`); do not cast
    // to platform Uri inside this model.
    fun isSameItem(other: ImageInfo): Boolean {
        return uri == other.uri
                && result == other.result
                && jobState == other.jobState
                && isInDelModel == other.isInDelModel
    }

    companion object {
        fun empty(): ImageInfo {
            return ImageInfo(
                MediaRef.Empty,
                1,
                1,
                1,
                1f,
                1f,
                null,
                JobState.Ready,
                false,
                0.5f,
                0.5f
            )
        }
    }
}
