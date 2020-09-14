package me.rosuh.easywatermark.utils

import android.content.ContentResolver
import android.net.Uri
import android.webkit.MimeTypeMap

class FileUtils {
    companion object {

        const val outPutFolderName = "EasyWaterMark"

        /**
         * 获取文件类型
         */
        @JvmStatic
        @Throws(SecurityException::class)
        fun getFileTypeFromUri(resolver: ContentResolver, uri: Uri?): String? {
            if (uri == null) {
                return null
            }
            return when {
                uri.scheme == "content" && resolver.getType(uri) != null -> {
                    resolver.getType(uri)
                }
                else -> {
                    // content provider 无法通过下面的方式获取到信息
                    val extension = MimeTypeMap.getFileExtensionFromUrl(uri.toString())
                    MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension)
                }
            }
        }

        private fun isImage(mimeType: String?): Boolean {
            return mimeType?.startsWith("image") ?: false
        }

        fun isImage(resolver: ContentResolver, uri: Uri?): Boolean {
            val mimeType = getFileTypeFromUri(resolver, uri)
            return isImage(mimeType)
        }
    }
}