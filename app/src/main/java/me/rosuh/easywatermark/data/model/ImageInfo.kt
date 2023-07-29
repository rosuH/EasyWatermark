package me.rosuh.easywatermark.data.model

import android.net.Uri
import androidx.annotation.FloatRange

data class ImageInfo(
    val uri: Uri,
    var width: Int = 1,
    var height: Int = 1,
    var inSample: Int = 1,
    var scaleX: Float = 1f,
    var scaleY: Float = 1f,
    var result: Result<*>? = null,
    var jobState: JobState = JobState.Ready,
    var isInDelModel: Boolean = false,
    @FloatRange(from = 0.0, to = 1.0) val offsetX: Float = 0.5f,
    @FloatRange(from = 0.0, to = 1.0) val offsetY: Float = 0.5f,
) {
    val shareUri: Uri?
        get() = result?.data as? Uri?

    fun isSameItem(other: ImageInfo): Boolean {
        return uri == other.uri
                && result == other.result
                && jobState == other.jobState
                && isInDelModel == other.isInDelModel
    }

    companion object {
        fun empty(): ImageInfo {
            return ImageInfo(
                Uri.EMPTY,
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
