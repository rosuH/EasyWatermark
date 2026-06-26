package me.rosuh.easywatermark.data.model

data class ImageInfo(
    // S4d-52: platform-neutral image identity (ADR-0007). Android `Uri` is converted at the edges
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
    // S4d-71: offsetX/offsetY are the normalized watermark offset; expected range 0f..1f (documented
    // invariant, unenforced). An Android-only doc/lint range annotation was dropped on the move to
    // commonMain (it had no runtime effect).
    val offsetX: Float = 0.5f,
    val offsetY: Float = 0.5f,
) {
    // S4d-53: the dead `shareUri: Uri?` computed accessor (`result?.data as? Uri?`) was removed —
    // it had zero call sites/reflection/tests, and the share-out button is an unwired empty lambda.
    // The actual export result still lives in `result`/`jobState` (untouched).
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
