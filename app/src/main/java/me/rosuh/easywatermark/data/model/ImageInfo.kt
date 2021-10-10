package me.rosuh.easywatermark.data.model

import android.net.Uri

data class ImageInfo(
    val uri: Uri,
    var width: Int = 1,
    var height: Int = 1,
    var scaleX: Float = 1f,
    var scaleY: Float = 1f,
    var result: Result<*>? = null,
    var jobState: JobState = JobState.Ready,
    var isInDelModel: Boolean = false,
) {
    val shareUri: Uri?
        get() = result?.data as? Uri?

    override fun toString(): String {
        return super.toString() + "\n $result"
    }
}
