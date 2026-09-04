package me.rosuh.easywatermark.utils.bitmap

import android.app.ActivityManager
import android.content.ContentResolver
import android.content.Context
import android.database.Cursor
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.graphics.Matrix.ScaleToFit
import android.net.Uri
import android.provider.MediaStore
import android.util.Log
import android.widget.ImageView
import android.widget.ImageView.ScaleType
import androidx.exifinterface.media.ExifInterface
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import me.rosuh.easywatermark.data.model.Result
import java.io.FileNotFoundException
import java.io.InputStream
import java.lang.ref.SoftReference
import kotlin.math.roundToInt

private const val TAG = "BitmapUtils"

private data class ExifTransform(val orientation: Int) {
    val swapsDimensions: Boolean get() = orientation in 5..8

    fun matrixOrNull(): Matrix? {
        return Matrix().apply {
            when (orientation) {
                ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> setScale(-1f, 1f)
                ExifInterface.ORIENTATION_ROTATE_180 -> setRotate(180f)
                ExifInterface.ORIENTATION_FLIP_VERTICAL -> {
                    setRotate(180f)
                    postScale(-1f, 1f)
                }
                ExifInterface.ORIENTATION_TRANSPOSE -> {
                    setRotate(90f)
                    postScale(-1f, 1f)
                }
                ExifInterface.ORIENTATION_ROTATE_90 -> setRotate(90f)
                ExifInterface.ORIENTATION_TRANSVERSE -> {
                    setRotate(-90f)
                    postScale(-1f, 1f)
                }
                ExifInterface.ORIENTATION_ROTATE_270 -> setRotate(-90f)
                else -> return null
            }
        }
    }
}

private fun decodeBitmapWithExifSync(
    inputStream: InputStream,
    transform: ExifTransform,
    options: BitmapFactory.Options? = null
): Result<BitmapCache.BitmapValue> {
    val bitmap = BitmapFactory.decodeStream(inputStream, null, options)
        ?: return Result.failure(null, "-1", "Generate Bitmap failed.")
    val inSampleSize = options?.inSampleSize ?: 1
    val matrix = transform.matrixOrNull()
        ?: return Result.success(BitmapCache.BitmapValue(bitmap, inSampleSize))

    val uprightBitmap = try {
        Bitmap.createBitmap(
            bitmap,
            0,
            0,
            bitmap.width,
            bitmap.height,
            matrix,
            false
        )
    } catch (failure: Throwable) {
        if (!bitmap.isRecycled) {
            bitmap.recycle()
        }
        throw failure
    }
    if (uprightBitmap != bitmap && !bitmap.isRecycled) {
        bitmap.recycle()
    }
    return Result.success(BitmapCache.BitmapValue(uprightBitmap, inSampleSize))
}

/** Read EXIF once per decode operation; MediaStore degrees are only a missing-EXIF fallback. */
private fun readExifTransform(
    resolver: ContentResolver,
    uri: Uri
): ExifTransform {
    val tagOrientation = runCatching {
        resolver.openInputStream(uri)?.use { input ->
            ExifInterface(input).getAttributeInt(
                ExifInterface.TAG_ORIENTATION,
                ExifInterface.ORIENTATION_UNDEFINED,
            )
        }
    }.getOrNull()
    if (tagOrientation != null && tagOrientation in 1..8) {
        return ExifTransform(tagOrientation)
    }

    val mediaStoreDegrees = try {
        resolver.query(
            uri,
            arrayOf(MediaStore.Images.ImageColumns.ORIENTATION),
            null,
            null,
            null,
        )?.use { cursor: Cursor ->
            if (cursor.count == 1 && cursor.moveToFirst()) cursor.getInt(0) else null
        }
    } catch (_: Exception) {
        null
    }
    val fallbackOrientation = when (mediaStoreDegrees) {
        90 -> ExifInterface.ORIENTATION_ROTATE_90
        180 -> ExifInterface.ORIENTATION_ROTATE_180
        270 -> ExifInterface.ORIENTATION_ROTATE_270
        else -> ExifInterface.ORIENTATION_NORMAL
    }
    return ExifTransform(fallbackOrientation)
}


suspend fun decodeBitmapFromUri(
    resolver: ContentResolver,
    uri: Uri
): Result<BitmapCache.BitmapValue> =
    withContext(Dispatchers.IO) {
        val transform = readExifTransform(resolver, uri)
        resolver.openInputStream(uri).use { inputStream ->
            if (inputStream == null) {
                return@withContext Result.failure(null, "-1", "Open input stream failed.")
            }
            return@withContext decodeBitmapWithExifSync(inputStream, transform)
        }
    }

suspend fun decodeSampledBitmapFromResource(
    resolver: ContentResolver,
    uri: Uri,
    reqWidth: Int,
    reqHeight: Int
): Result<BitmapCache.BitmapValue> = withContext(Dispatchers.IO) {
    val info = BitmapCache.BitmapInfo(uri, reqWidth, reqHeight)
    val cacheValue = BitmapCache.getFromCache(info)
    if (cacheValue?.bitmap != null) {
        Log.i("BitmapUtils", "Hit the cache bitmap!")
        return@withContext Result.success(data = cacheValue)
    }

    val decodeResult = decodeSampledBitmapFromResourceSync(
        resolver,
        uri,
        reqWidth,
        reqHeight
    )
    val decoded = decodeResult.data
    if (decoded?.bitmap == null) {
        return@withContext Result.failure(null, decodeResult.code, decodeResult.message)
    }

    BitmapCache.addToCache(info, decoded)
    return@withContext Result.success(data = decoded)
}

/**
 * Editor-preview Source decode. Bypasses [BitmapCache] so the preview working set is
 * the single owner of the focus frame. Never recycle the result.
 */
/** Bounds-only encoded size. Returns (-1, -1) if the stream cannot be read. */
fun probeEncodedSize(resolver: ContentResolver, uri: Uri): Pair<Int, Int> {
    return try {
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openInputStream(uri).use { stream ->
            if (stream == null) return -1 to -1
            BitmapFactory.decodeStream(stream, null, options)
        }
        options.outWidth to options.outHeight
    } catch (_: Exception) {
        -1 to -1
    }
}

fun decodePreviewSourceBypassingCache(
    resolver: ContentResolver,
    uri: Uri,
    reqWidth: Int,
    reqHeight: Int,
): Result<BitmapCache.BitmapValue> {
    me.rosuh.easywatermark.render.PreviewSourceReuseProbe.recordSourceDecode()
    me.rosuh.easywatermark.render.PreviewSourceReuseProbe.recordContentResolverOpen()
    return decodeSampledBitmapFromResourceSync(resolver, uri, reqWidth, reqHeight)
}

fun decodeSampledBitmapFromResourceSync(
    resolver: ContentResolver,
    uri: Uri,
    reqWidth: Int,
    reqHeight: Int
): Result<BitmapCache.BitmapValue> {
    try {
        val transform = readExifTransform(resolver, uri)
        val options = BitmapFactory.Options()
        options.inJustDecodeBounds = true
        // 1. decode bounds only
        resolver.openInputStream(uri).use { `is` ->
            BitmapFactory.decodeStream(`is`, null, options)
        }
        // 2. Calculate inSampleSize
        val (oHeight: Int, oWidth: Int) = if (transform.swapsDimensions) {
            options.run { outWidth to outHeight }
        } else {
            options.run { outHeight to outWidth }
        }
        options.inSampleSize = calculateInSampleSize(oWidth, oHeight, reqWidth, reqHeight)
        Log.i(TAG, "reqW x reqH = $reqWidth x $reqHeight, outWidth x outHeight = $oWidth x $oHeight, inSampleSize = ${options.inSampleSize}")
        // 3. Decode bitmap with inSampleSize set
        options.inJustDecodeBounds = false
        resolver.openInputStream(uri).use { inputStream ->
            if (inputStream == null) {
                return Result.failure(null, "-1", "Open input stream failed.")
            }
            return decodeBitmapWithExifSync(inputStream, transform, options)
        }
    } catch (fne: FileNotFoundException) {
        return Result.failure(null, "-1", fne.message)
    } catch (se: SecurityException) {
        throw se
    } catch (e: Exception) {
        Log.i("BitmapUtils", "Decoding sampled bitmap from resource failed", e)
        return Result.failure(null, "-1", e.message)
    } catch (oom: OutOfMemoryError) {
        Log.i("BitmapUtils", "Decoding sampled bitmap from resource throw oom")
        return Result.failure(
            null,
            "-1",
            "Decoding sampled bitmap from resource throw oom"
        )
    }
}

fun calculateInSampleSize(
    width: Int,
    height: Int,
    reqWidth: Int,
    reqHeight: Int
): Int {
    // Raw height and width of image
    Log.i(
        "generateImage", "w = $width, h = $height, reqW = $reqWidth, reqH = $reqHeight"
    )
    var inSampleSize = 1

    if (height > reqHeight || width > reqWidth) {

        val halfHeight: Int = height / 2
        val halfWidth: Int = width / 2

        // Calculate the largest inSampleSize value that is a power of 2 and keeps both
        // height and width larger than the requested height and width.
        while (halfHeight / inSampleSize >= reqHeight && halfWidth / inSampleSize >= reqWidth) {
            inSampleSize *= 2
        }

// var totalPixels = (width / inSampleSize) * (height / inSampleSize)
// val totalReqPixels = reqWidth * reqHeight * 2
// while (totalPixels > totalReqPixels) {
// inSampleSize *= 2;
// Log.i(TAG, "totalPixels = $totalPixels, totalReqPixels = $totalReqPixels, inSample -> $inSampleSize")
// totalPixels = (width / inSampleSize) * (height / inSampleSize)
// }
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
    val byteCount = width * height * getBytesInPixel(candidate.config ?: Bitmap.Config.ARGB_8888)
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



fun scaleTypeToScaleToFit(st: ScaleType): ScaleToFit {
    // ScaleToFit enum to their corresponding Matrix.ScaleToFit values
    return sS2FArray[st.toNativeInt() - 1]
}

private val sS2FArray = arrayOf(
    ScaleToFit.FILL,
    ScaleToFit.START,
    ScaleToFit.CENTER,
    ScaleToFit.END
)

fun ScaleType.toNativeInt(): Int {
    return when (this) {
        ScaleType.MATRIX -> 0
        ScaleType.FIT_XY -> 1
        ScaleType.FIT_START -> 2
        ScaleType.FIT_CENTER -> 3
        ScaleType.FIT_END -> 4
        ScaleType.CENTER -> 5
        ScaleType.CENTER_CROP -> 6
        ScaleType.CENTER_INSIDE -> 7
    }
}
