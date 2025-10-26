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
            return try {
                val mimeType = getFileTypeFromUri(resolver, uri)
                isImage(mimeType)
            } catch (e: SecurityException) {
                // Handle SecurityException on some Android systems (e.g., MIUI)
                // where ContentResolver.getType() might be restricted
                // Fall back to checking file extension
                isImageByExtension(uri)
            }
        }

        /**
         * Fallback method to check if URI is an image by examining the file extension
         * when MIME type detection fails due to security restrictions
         */
        private fun isImageByExtension(uri: Uri?): Boolean {
            if (uri == null) return false

            val extension = MimeTypeMap.getFileExtensionFromUrl(uri.toString())?.lowercase()
            if (extension.isNullOrEmpty()) return false

            val imageExtensions = setOf(
                "jpg", "jpeg", "png", "gif", "bmp", "webp",
                "tiff", "tif", "svg", "ico", "heic", "heif"
            )

            return imageExtensions.contains(extension)
        }
    }
}
