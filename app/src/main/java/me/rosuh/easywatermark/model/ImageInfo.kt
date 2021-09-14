package me.rosuh.easywatermark.model

import android.net.Uri

data class ImageInfo(
    val uri: Uri,
    var result: Result<*>? = null
) {
    val shareUri: Uri?
        get() = result?.data as? Uri?

    override fun toString(): String {
        return super.toString() + "\n $result"
    }
}
