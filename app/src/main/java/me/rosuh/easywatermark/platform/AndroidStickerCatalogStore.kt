package me.rosuh.easywatermark.platform

import android.content.Context
import me.rosuh.easywatermark.data.model.StickerCatalog
import java.io.File

internal object AndroidStickerCatalogStore {
    fun directory(context: Context): File = File(context.filesDir, "watermark_icons")

    fun readText(context: Context): String {
        val file = File(directory(context), StickerCatalog.FILE_NAME)
        return if (file.isFile) file.readText() else ""
    }

    fun writeText(context: Context, text: String) {
        val dir = directory(context)
        check(dir.exists() || dir.mkdirs()) { "Unable to create private icon directory" }
        File(dir, StickerCatalog.FILE_NAME).writeText(text)
    }
}
