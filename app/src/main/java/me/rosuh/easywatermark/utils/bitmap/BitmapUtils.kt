package me.rosuh.easywatermark.utils.bitmap

import android.app.ActivityManager
import android.content.ContentResolver
import android.content.Context
import android.database.Cursor
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.graphics.Matrix.ScaleToFit
import android.graphics.Rect
import android.graphics.RectF
import android.net.Uri
import android.provider.MediaStore
import android.util.Log
import android.widget.ImageView
import android.widget.ImageView.ScaleType
import androidx.exifinterface.media.ExifInterface
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import me.rosuh.easywatermark.MyApp
import me.rosuh.easywatermark.data.model.Result
import me.rosuh.easywatermark.data.model.ViewInfo
import java.io.FileNotFoundException
import java.io.InputStream
import java.lang.ref.SoftReference
import kotlin.math.roundToInt

private const val TAG = "BitmapUtils"

suspend fun decodeBitmapWithExif(
    uri: Uri,
    inputStream: InputStream,
    options: BitmapFactory.Options? = null,
): Result<BitmapCache.BitmapValue> =
    withContext(Dispatchers.IO) {
        return@withContext decodeBitmapWithExifSync(uri, inputStream, options)
    }

fun decodeBitmapWithExifSync(
    uri: Uri,
    inputStream: InputStream,
    options: BitmapFactory.Options? = null
): Result<BitmapCache.BitmapValue> {
    val bitmap = BitmapFactory.decodeStream(inputStream, null, options)
        ?: return Result.failure(null, "-1", "Generate Bitmap failed.")
    val inSampleSize = options?.inSampleSize ?: 1
    val bitmapValue = BitmapCache.BitmapValue(bitmap, inSampleSize)

    val rotation = getOrientation(MyApp.instance, uri)
    if (rotation == 0f) {
        return Result.success(bitmapValue)
    }

    val matrix = Matrix()
    matrix.postRotate(rotation)

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
    val rotateBitmapValue = BitmapCache.BitmapValue(rotatedBitmap, inSampleSize)
    return Result.success(rotateBitmapValue)
}

/**
 * Get orientation from ExifInterface and System sql.
 */
private fun getOrientation(
    context: Context,
    uri: Uri
): Float {
    context.contentResolver.openInputStream(uri).use {
        if (it == null) {
            return 0f
        }
        val exif = if (android.os.Build.VERSION.SDK_INT > android.os.Build.VERSION_CODES.N) {
            try {
                ExifInterface(it)
            } catch (e: Exception) {
                e.printStackTrace()
                null
            }
        } else {
            // do not support api lower 24
            null
        }
        val tagOrientation: Int = exif?.getAttributeInt(
            ExifInterface.TAG_ORIENTATION,
            ExifInterface.ORIENTATION_UNDEFINED
        ) ?: ExifInterface.ORIENTATION_UNDEFINED

        when (tagOrientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> {
                return 90f
            }
            ExifInterface.ORIENTATION_ROTATE_180 -> {
                return 180f
            }
            ExifInterface.ORIENTATION_ROTATE_270 -> {
                return 270f
            }
            else -> {
                // do not need to rotate bitmap
                try {
                    val cursor: Cursor? = context.contentResolver.query(
                        uri,
                        arrayOf(MediaStore.Images.ImageColumns.ORIENTATION),
                        null,
                        null,
                        null
                    )
                    if (cursor?.count != 1) {
                        cursor?.close()
                        return 0f
                    }
                    cursor.moveToFirst()
                    val orientation: Int = cursor.getInt(0)
                    cursor.close()
                    return orientation.toFloat()
                } catch (e: Exception) {
                    return 0f
                }
            }
        }
    }
}


suspend fun decodeBitmapFromUri(
    resolver: ContentResolver,
    uri: Uri
): Result<BitmapCache.BitmapValue> =
    withContext(Dispatchers.IO) {
        resolver.openInputStream(uri).use { inputStream ->
            if (inputStream == null) {
                return@withContext Result.failure(null, "-1", "Open input stream failed.")
            }
            return@withContext decodeBitmapWithExif(uri, inputStream)
        }
    }

suspend fun decodeSampledBitmapFromResource(
    resolver: ContentResolver,
    uri: Uri,
    reqWidth: Int,
    reqHeight: Int
): Result<BitmapCache.BitmapValue> = withContext(Dispatchers.IO) {
    val info = BitmapCache.BitmapInfo(uri, reqWidth, reqHeight)
    var cacheValue = BitmapCache.getFromCache(info)
    if (cacheValue?.bitmap == null) {
        cacheValue = decodeSampledBitmapFromResourceSync(
            resolver,
            uri,
            reqWidth,
            reqHeight
        ).data
        BitmapCache.addToCache(info, cacheValue)
    } else {
        Log.i("BitmapUtils", "Hit the cache bitmap!")
    }
    return@withContext Result.success(data = cacheValue)
}

fun decodeSampledBitmapFromResourceSync(
    resolver: ContentResolver,
    uri: Uri,
    reqWidth: Int,
    reqHeight: Int
): Result<BitmapCache.BitmapValue> {
    try {
        val options = BitmapFactory.Options()
        options.inJustDecodeBounds = true
        // 1. decode bounds only
        resolver.openInputStream(uri).use { `is` ->
            BitmapFactory.decodeStream(`is`, null, options)
        }
        // 2. Calculate inSampleSize
        val (oHeight: Int, oWidth: Int) = if (interChangeSize(MyApp.instance, uri)) {
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
            return decodeBitmapWithExifSync(uri, inputStream, options)
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

fun interChangeSize(context: Context, uri: Uri): Boolean {
    val rotation = getOrientation(context, uri)
    if (rotation == 90f || rotation == 180f) {
        return true
    }
    return false
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
    var inSampleSize = 2

    if (height > reqHeight || width > reqWidth) {

        val halfHeight: Int = height / 2
        val halfWidth: Int = width / 2

        // Calculate the largest inSampleSize value that is a power of 2 and keeps both
        // height and width larger than the requested height and width.
        while (halfHeight / inSampleSize >= reqHeight && halfWidth / inSampleSize >= reqWidth) {
            inSampleSize *= 2
        }

//        var totalPixels = (width / inSampleSize) * (height / inSampleSize)
//        val totalReqPixels = reqWidth * reqHeight * 2
//        while (totalPixels > totalReqPixels) {
//            inSampleSize *= 2;
//            Log.i(TAG, "totalPixels = $totalPixels, totalReqPixels = $totalReqPixels, inSample -> $inSampleSize")
//            totalPixels = (width / inSampleSize) * (height / inSampleSize)
//        }
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

/**
 * @author hi@rosuh.me
 * @date 2021/10/16
 * Copy from [ImageView]
 */
fun generateMatrix(
    viewInfo: ViewInfo,
    drawableWidth: Int,
    drawableHeight: Int,
    bounds: Rect,
    tempSrc: RectF,
    tempDst: RectF,
): Matrix {
    val dwidth: Int = drawableWidth
    val dheight: Int = drawableHeight
    val vwidth: Int = viewInfo.width - viewInfo.paddingLeft - viewInfo.paddingRight
    val vheight: Int = viewInfo.height - viewInfo.paddingTop - viewInfo.paddingBottom
    val fits = ((dwidth < 0 || vwidth == dwidth)
            && (dheight < 0 || vheight == dheight))
    var mDrawMatrix = Matrix()
    if (dwidth <= 0 || dheight <= 0 || ScaleType.FIT_XY == viewInfo.scaleType) {
        /* If the drawable has no intrinsic size, or we're told to
                scaletofit, then we just fill our entire view.
            */
        bounds.set(0, 0, vwidth, vheight)
    } else {
        // We need to do the scaling ourself, so have the drawable
        // use its native size.
        bounds.set(0, 0, dwidth, dheight)
        if (ScaleType.MATRIX == viewInfo.scaleType) {
            // Use the specified matrix as-is.
            if (!viewInfo.matrix.isIdentity) {
                mDrawMatrix = viewInfo.matrix
            }
        } else if (fits) {
            // The bitmap fits exactly, no transform needed.
        } else if (ScaleType.CENTER == viewInfo.scaleType) {
            // Center bitmap in view, no scaling.
            mDrawMatrix = viewInfo.matrix
            mDrawMatrix.setTranslate(
                ((vwidth - dwidth) * 0.5f).roundToInt().toFloat(),
                ((vheight - dheight) * 0.5f).roundToInt().toFloat()
            )
        } else if (ScaleType.CENTER_CROP == viewInfo.scaleType) {
            mDrawMatrix = viewInfo.matrix
            val scale: Float
            var dx = 0f
            var dy = 0f
            if (dwidth * vheight > vwidth * dheight) {
                scale = vheight.toFloat() / dheight.toFloat()
                dx = (vwidth - dwidth * scale) * 0.5f
            } else {
                scale = vwidth.toFloat() / dwidth.toFloat()
                dy = (vheight - dheight * scale) * 0.5f
            }
            mDrawMatrix.setScale(scale, scale)
            mDrawMatrix.postTranslate(Math.round(dx).toFloat(), Math.round(dy).toFloat())
        } else if (ScaleType.CENTER_INSIDE == viewInfo.scaleType) {
            mDrawMatrix = viewInfo.matrix
            val dx: Float
            val dy: Float
            val scale: Float = if (dwidth <= vwidth && dheight <= vheight) {
                1.0f
            } else {
                (vwidth.toFloat() / dwidth.toFloat()).coerceAtMost(vheight.toFloat() / dheight.toFloat())
            }
            dx = ((vwidth - dwidth * scale) * 0.5f).roundToInt().toFloat()
            dy = ((vheight - dheight * scale) * 0.5f).roundToInt().toFloat()
            mDrawMatrix.setScale(scale, scale)
            mDrawMatrix.postTranslate(dx, dy)
        } else {
            // Generate the required transform.
            tempSrc.set(0f, 0f, dwidth.toFloat(), dheight.toFloat())
            tempDst.set(0f, 0f, vwidth.toFloat(), vheight.toFloat())
            mDrawMatrix = viewInfo.matrix
            mDrawMatrix.setRectToRect(
                tempSrc,
                tempDst,
                scaleTypeToScaleToFit(viewInfo.scaleType)
            )
        }
    }
    return mDrawMatrix
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