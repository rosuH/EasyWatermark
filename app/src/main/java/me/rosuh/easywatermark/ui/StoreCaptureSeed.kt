package me.rosuh.easywatermark.ui

import android.content.Context
import android.net.Uri
import android.provider.MediaStore

/**
 * Simulator / emulator store-capture only. Host scripts push
 * `ewm_store_*` images to Pictures, then launch with
 * `--es storeSeedScene <photo|style|color|layout|idcard|templates|export>`.
 */
internal object StoreCaptureSeed {
    const val EXTRA_SCENE = "storeSeedScene"
    const val NAME_PREFIX = "ewm_store_"

    data class Chrome(val tab: Int, val option: Int)

    fun chromeFor(scene: String): Chrome =
        when (scene) {
            "style" -> Chrome(tab = 1, option = 2)
            "color" -> Chrome(tab = 1, option = 3)
            "layout" -> Chrome(tab = 2, option = 0)
            "idcard" -> Chrome(tab = 1, option = 2)
            else -> Chrome(tab = 0, option = 0)
        }

    /** Filmstrip index: ID is first, Skytree is second (matches iOS seed). */
    fun imageIndexFor(scene: String): Int =
        if (scene == "idcard") 0 else 1

    fun querySeedUris(context: Context): List<Uri> {
        val out = ArrayList<Uri>(4)
        context.contentResolver.query(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            arrayOf(MediaStore.Images.Media._ID, MediaStore.Images.Media.DISPLAY_NAME),
            "${MediaStore.Images.Media.DISPLAY_NAME} LIKE ?",
            arrayOf("$NAME_PREFIX%"),
            "${MediaStore.Images.Media.DISPLAY_NAME} ASC",
        )?.use { cursor ->
            val idCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
            while (cursor.moveToNext()) {
                out += MediaStore.Images.Media.EXTERNAL_CONTENT_URI.buildUpon()
                    .appendPath(cursor.getLong(idCol).toString())
                    .build()
            }
        }
        return out
    }
}
