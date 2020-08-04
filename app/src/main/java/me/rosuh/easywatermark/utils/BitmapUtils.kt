package me.rosuh.easywatermark.utils

import android.content.ContentResolver
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.FileNotFoundException
import java.lang.Exception


@Synchronized
suspend fun decodeBitmapFromUri(resolver: ContentResolver, uri: Uri): Bitmap? =
    withContext(Dispatchers.IO) {
        try {
            resolver.openInputStream(uri).use {
                return@use BitmapFactory.decodeStream(it)
            }
        } catch (fne: FileNotFoundException) {
            return@withContext null
        } catch (oom: OutOfMemoryError) {
            Log.i(this::class.simpleName, "copyImage oom")
            return@withContext null
        }
    }

suspend fun decodeSampledBitmapFromResource(
    resolver: ContentResolver,
    uri: Uri,
    reqWidth: Int,
    reqHeight: Int
): Bitmap? = withContext(Dispatchers.IO) {
    try {
        return@withContext BitmapFactory.Options().run {
            inJustDecodeBounds = true
            resolver.openInputStream(uri).use { `is` ->
                BitmapFactory.decodeStream(`is`, null, this)
            }

            // Calculate inSampleSize
            inSampleSize = calculateInSampleSize(this, reqWidth, reqHeight)

            // Decode bitmap with inSampleSize set
            inJustDecodeBounds = false

            resolver.openInputStream(uri).use { `is` ->
                return@use BitmapFactory.decodeStream(`is`, null, this)
            }
        }
    } catch (fne: FileNotFoundException) {
        return@withContext null
    } catch (oom: OutOfMemoryError) {
        Log.i(this::class.simpleName, "Decoding sampled bitmap from resource throw oom")
        return@withContext null
    }
    // First decode with inJustDecodeBounds=true to check dimensions
}

fun calculateInSampleSize(options: BitmapFactory.Options, reqWidth: Int, reqHeight: Int): Int {
    // Raw height and width of image
    val (height: Int, width: Int) = options.run { outHeight to outWidth }
    var inSampleSize = 1

    val isLand = options.outWidth > options.outHeight
    val ratio = options.outWidth.toFloat() / options.outHeight

    val ratioWidth = if (isLand) {
        options.outWidth.coerceAtMost(reqWidth)
    } else {
        (options.outHeight.coerceAtMost(reqHeight) * ratio).toInt()
    }

    val ratioHeight = if (isLand) {
        (options.outWidth.coerceAtMost(reqWidth) / ratio).toInt()
    } else {
        options.outHeight.coerceAtMost(reqHeight)
    }

    if (height > ratioHeight || width > ratioWidth) {

        val halfHeight: Int = height / 2
        val halfWidth: Int = width / 2

        // Calculate the largest inSampleSize value that is a power of 2 and keeps both
        // height and width larger than the requested height and width.
        while (halfHeight / inSampleSize >= ratioHeight && halfWidth / inSampleSize >= ratioWidth) {
            inSampleSize *= 2
        }
    }

    return inSampleSize
}

fun getFreeMemory(): Long {
    return try {
        val runtime = Runtime.getRuntime()
        val free = runtime.freeMemory() / 0x100000L
        Log.i("Utils", "availableMegs = $free")
        free
    } catch (e: Exception) {
        0L
    }
}