package me.rosuh.easywatermark.utils

import android.content.Context
import android.os.Environment
import android.util.Log
import java.io.File

fun getAppSpecificAlbumStorageDir(context: Context, albumName: String): File? {
    // Get the pictures directory that's inside the app-specific directory on
    // external storage.
    val file = File(
        context.getExternalFilesDir(
            Environment.DIRECTORY_PICTURES
        ), albumName
    )
    if (!file.mkdirs()) {
        Log.e("FileUtils", "Directory not created")
    }
    return file
}