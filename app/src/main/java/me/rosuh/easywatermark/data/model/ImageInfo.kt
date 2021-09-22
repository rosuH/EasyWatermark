package me.rosuh.easywatermark.data.model

import android.net.Uri

data class ImageInfo(
    val uri: Uri,
    var result: Result<*>? = null,
    var isInDelModel: Boolean = false,
) {
    val shareUri: Uri?
        get() = result?.data as? Uri?

    override fun toString(): String {
        return super.toString() + "\n $result"
    }
}
