package me.rosuh.easywatermark.utils

import android.app.ActivityManager
import android.content.ContentResolver
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import android.util.Log
import androidx.exifinterface.media.ExifInterface
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.FileNotFoundException
import java.io.InputStream
import java.lang.ref.SoftReference
import kotlin.math.roundToInt

suspend fun decodeBitmapWithExif(
    inputStream: InputStream,
    options: BitmapFactory.Options? = null,
    scale: FloatArray = FloatArray(2) { 1f }
): Result<Bitmap> =
    withContext(Dispatchers.IO) {
        return@withContext decodeBitmapWithExifSync(inputStream, options, scale)
    }

fun decodeBitmapWithExifSync(
    inputStream: InputStream,
    options: BitmapFactory.Options? = null,
    scale: FloatArray = FloatArray(2) { 1f }
): Result<Bitmap> {
    val bitmap = BitmapFactory.decodeStream(inputStream, null, options)
        ?: return Result.failure(null, "-1", "Generate Bitmap failed.")
    if (options != null) {
        scale[0] = options.inSampleSize.toFloat()
        scale[1] = options.inSampleSize.toFloat()
    }
    val exif = if (android.os.Build.VERSION.SDK_INT > android.os.Build.VERSION_CODES.N) {
        try {
            ExifInterface(inputStream)
        } catch (e: Exception) {
            e.printStackTrace()
            return Result.success(bitmap)
        }
    } else {
        // do not support api lower 24
        return Result.success(bitmap)
    }

    val orientation: Int = exif.getAttributeInt(
        ExifInterface.TAG_ORIENTATION,
        ExifInterface.ORIENTATION_UNDEFINED
    )

    val matrix = Matrix()
    when (orientation) {
        ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90f)
        ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
        ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270f)
        ExifInterface.ORIENTATION_UNDEFINED, ExifInterface.ORIENTATION_NORMAL -> {
            // do not need to rotate bitmap
            return Result.success(bitmap)
        }
        else -> {
        }
    }

    val rotatedBitmap = Bitmap.createBitmap(
        bitmap,
        0,
        0,
        bitmap.width,
        bitmap.height,
        matrix,
        false
    )
    if (rotatedBitmap != bitmap && !bitmap.isRecycled) {
        bitmap.recycle()
    }
    return Result.success(rotatedBitmap)
}

suspend fun decodeBitmapFromUri(resolver: ContentResolver, uri: Uri): Result<Bitmap> =
    withContext(Dispatchers.IO) {
        resolver.openInputStream(uri).use { inputStream ->
            if (inputStream == null) {
                return@withContext Result.failure(null, "-1", "Open input stream failed.")
            }
            return@withContext decodeBitmapWithExif(inputStream)
        }
    }

suspend fun decodeSampledBitmapFromResource(
    resolver: ContentResolver,
    uri: Uri,
    reqWidth: Int,
    reqHeight: Int,
    scale: FloatArray = FloatArray(2) { 1f }
): Result<Bitmap> = withContext(Dispatchers.IO) {
    return@withContext decodeSampledBitmapFromResourceSync(
        resolver,
        uri,
        reqWidth,
        reqHeight,
        scale
    )
}

fun decodeSampledBitmapFromResourceSync(
    resolver: ContentResolver,
    uri: Uri,
    reqWidth: Int,
    reqHeight: Int,
    scale: FloatArray = FloatArray(2) { 1f }
): Result<Bitmap> {
    try {
        val options = BitmapFactory.Options()
        options.inJustDecodeBounds = true
        // 1. decode bounds only
        resolver.openInputStream(uri).use { `is` ->
            BitmapFactory.decodeStream(`is`, null, options)
        }
        // 2. Calculate inSampleSize
        options.inSampleSize = calculateInSampleSizeAccurate(options, reqWidth, reqHeight)
        // 3. Decode bitmap with inSampleSize set
        options.inJustDecodeBounds = false
        resolver.openInputStream(uri).use { inputStream ->
            if (inputStream == null) {
                return Result.failure(null, "-1", "Open input stream failed.")
            }
            return decodeBitmapWithExifSync(inputStream, options, scale)
        }
    } catch (fne: FileNotFoundException) {
        return Result.failure(null, "-1", fne.message)
    } catch (oom: OutOfMemoryError) {
        Log.i("BitmapUtils", "Decoding sampled bitmap from resource throw oom")
        return Result.failure(
            null,
            "-1",
            "Decoding sampled bitmap from resource throw oom"
        )
    }
}

fun calculateInSampleSizeAccurate(
    options: BitmapFactory.Options,
    reqWidth: Int,
    reqHeight: Int
): Int {
    // Raw height and width of image
    val (height: Int, width: Int) = options.run { outHeight to outWidth }
    var inSampleSize = 2

    if (height > reqHeight || width > reqWidth) {

        val halfHeight: Int = height / 2
        val halfWidth: Int = width / 2

        // Calculate the largest inSampleSize value that is a power of 2 and keeps both
        // height and width larger than the requested height and width.
        while (halfHeight / inSampleSize >= reqHeight && halfWidth / inSampleSize >= reqWidth) {
            inSampleSize *= 2
        }
    }

    return inSampleSize
}

fun calculateInSampleSize(options: BitmapFactory.Options, reqWidth: Int, reqHeight: Int): Int {
    // Raw height and width of image
    val (height: Int, width: Int) = options.run { outHeight to outWidth }
    var inSampleSize = 1

    if (height > reqHeight || width > reqHeight) {
        // 计算出实际宽高和目标宽高的比率
        val heightRatio = (height.toFloat() / reqHeight).roundToInt()
        val widthRatio = (width.toFloat() / reqWidth).roundToInt()
        inSampleSize = if (heightRatio < widthRatio) heightRatio else widthRatio
    }

    return inSampleSize
}

// Get a MemoryInfo object for the device's current memory status.
fun getAvailableMemory(context: Context): ActivityManager.MemoryInfo {
    val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
    return ActivityManager.MemoryInfo().also { memoryInfo ->
        activityManager.getMemoryInfo(memoryInfo)
    }
}

fun addInBitmapOptions(
    options: BitmapFactory.Options,
    reusableBitmaps: HashSet<SoftReference<Bitmap>>
) {
    options.inMutable = true
    getBitmapFromReusableSet(options, reusableBitmaps)?.also { inBitmap ->
        options.inBitmap = inBitmap
    }
}

fun getBitmapFromReusableSet(
    options: BitmapFactory.Options,
    reusableBitmaps: HashSet<SoftReference<Bitmap>>
): Bitmap? {
    synchronized(reusableBitmaps) {
        val iterator = reusableBitmaps.iterator()
        while (iterator.hasNext()) {
            iterator.next().get()?.let { item ->
                when {
                    !item.isMutable -> {
                        iterator.remove()
                    }
                    canUseForInBitmap(item, options) -> {
                        iterator.remove()
                        return item
                    }
                }
            }
        }
        return null
    }
}

/**
 * Only the size equals or larger target options can be reused.
 * @author hi@rosuh.me
 * @date 2021/8/16
 */
private fun canUseForInBitmap(
    candidate: Bitmap,
    targetOptions: BitmapFactory.Options
): Boolean {
    val width = targetOptions.outWidth / targetOptions.inSampleSize
    val height = targetOptions.outHeight / targetOptions.inSampleSize
    val byteCount = width * height * getBytesInPixel(candidate.config)
    return byteCount <= candidate.allocationByteCount
}

private fun getBytesInPixel(config: Bitmap.Config): Int {
    return when (config) {
        Bitmap.Config.ALPHA_8 -> 1
        Bitmap.Config.RGB_565, Bitmap.Config.ARGB_4444 -> 2
        Bitmap.Config.ARGB_8888 -> 4
        else -> 1
    }
}