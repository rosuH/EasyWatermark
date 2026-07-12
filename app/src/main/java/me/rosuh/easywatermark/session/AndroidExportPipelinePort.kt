package me.rosuh.easywatermark.session

import android.content.ContentResolver
import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.text.TextPaint
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import me.rosuh.easywatermark.BuildConfig
import me.rosuh.easywatermark.MyApp
import me.rosuh.easywatermark.data.model.ImageInfo
import me.rosuh.easywatermark.data.model.MediaRef
import me.rosuh.easywatermark.data.model.Result
import me.rosuh.easywatermark.data.model.UserPreferences
import me.rosuh.easywatermark.data.model.WaterMark
import me.rosuh.easywatermark.data.model.WatermarkMode
import me.rosuh.easywatermark.render.WatermarkRenderer
import me.rosuh.easywatermark.render.androidTextMeasureEnv
import me.rosuh.easywatermark.utils.FileUtils.Companion.outPutFolderName
import me.rosuh.easywatermark.utils.bitmap.decodeBitmapFromUri
import me.rosuh.easywatermark.utils.bitmap.decodeSampledBitmapFromResource
import me.rosuh.easywatermark.utils.ktx.applyConfig
import me.rosuh.easywatermark.utils.ktx.obtainTileMode
import me.rosuh.easywatermark.utils.ktx.toCompressFormat
import me.rosuh.easywatermark.utils.ktx.toMediaRef
import me.rosuh.easywatermark.utils.ktx.toUri
import java.io.File
import java.io.FileNotFoundException
import java.io.FileOutputStream

/**
 * Android [ExportPipelinePort]: **verbatim** wrap of legacy [me.rosuh.easywatermark.ui.MainViewModel]
 * `generateImage` (native WatermarkRenderer + MediaStore / pre-Q file path).
 * ADR-0017 performance rule: wrap-not-rewrite.
 */
class AndroidExportPipelinePort(
    private val appContext: Context,
    private val contentResolver: ContentResolver = appContext.contentResolver,
) : ExportPipelinePort {

    override suspend fun exportOne(
        imageInfo: ImageInfo,
        config: WaterMark,
        prefs: UserPreferences,
    ): Result<MediaRef> = withContext(Dispatchers.IO) {
        try {
            exportOneInternal(imageInfo, config, prefs)
        } catch (_: FileNotFoundException) {
            Result.failure(null, code = ExportErrorCodes.FILE_NOT_FOUND)
        } catch (_: OutOfMemoryError) {
            Result.failure(null, code = ExportErrorCodes.SAVE_OOM)
        }
    }

    private suspend fun exportOneInternal(
        imageInfo: ImageInfo,
        config: WaterMark,
        prefs: UserPreferences,
    ): Result<MediaRef> {
        val rect = decodeBitmapFromUri(contentResolver, imageInfo.uri.toUri())
        if (rect.isFailure()) {
            return Result.extendMsg(rect)
        }
        val mutableBitmap = rect.data?.bitmap?.copy(Bitmap.Config.ARGB_8888, true)
            ?: return Result.failure(
                null,
                code = "-1",
                message = "Copy bitmap from uri failed.",
            )

        imageInfo.width = mutableBitmap.width
        imageInfo.height = mutableBitmap.height
        val canvas = Canvas(mutableBitmap)
        val bitmapPaint = TextPaint().applyConfig(imageInfo, config, isScale = false)
        val layoutPaint = Paint()
        val shader = when (config.markMode) {
            WatermarkMode.Text -> {
                WatermarkRenderer.buildTextShader(
                    imageInfo,
                    config,
                    bitmapPaint,
                    androidTextMeasureEnv(appContext),
                    Dispatchers.IO,
                )
            }

            WatermarkMode.Image -> {
                val iconBitmapRect = decodeSampledBitmapFromResource(
                    contentResolver,
                    config.iconUri.toUri(),
                    imageInfo.width,
                    imageInfo.height,
                )
                if (iconBitmapRect.isFailure() || iconBitmapRect.data == null) {
                    return Result.failure(
                        null,
                        code = "-1",
                        message = "decodeSampledBitmapFromResource == null",
                    )
                }
                val iconBitmap = iconBitmapRect.data!!.bitmap
                WatermarkRenderer.buildIconShader(
                    imageInfo,
                    iconBitmap,
                    config,
                    bitmapPaint,
                    scale = true,
                    Dispatchers.IO,
                )
            }
        }

        WatermarkRenderer.compose(
            canvas = canvas,
            shader = shader,
            tileMode = config.obtainTileMode(),
            paint = layoutPaint,
            left = 0f,
            top = 0f,
            regionWidth = mutableBitmap.width.toFloat(),
            regionHeight = mutableBitmap.height.toFloat(),
            offsetX = imageInfo.offsetX,
            offsetY = imageInfo.offsetY,
        )

        val outputFormat = prefs.outputFormat
        val compressLevel = prefs.compressLevel
        val fileExt = outputFormat.fileExtension
        val displayName = "ewm_${System.currentTimeMillis()}.$fileExt"

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val imageCollection =
                MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            val imageDetail = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, displayName)
                put(MediaStore.Images.Media.MIME_TYPE, "image/$fileExt")
                put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/$outPutFolderName/")
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }

            val imageContentUri = contentResolver.insert(imageCollection, imageDetail)
            contentResolver.openFileDescriptor(imageContentUri!!, "w", null).use { pfd ->
                mutableBitmap.compress(
                    outputFormat.toCompressFormat(),
                    compressLevel,
                    FileOutputStream(pfd!!.fileDescriptor),
                )
            }
            imageDetail.clear()
            imageDetail.put(MediaStore.Images.Media.IS_PENDING, 0)
            contentResolver.update(imageContentUri, imageDetail, null, null)
            Result.success(imageContentUri.toMediaRef())
        } else {
            val picturesFile: File =
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES)
                    ?: return Result.failure(
                        null,
                        code = "-1",
                        message = "Can't get pictures directory.",
                    )
            if (!picturesFile.exists()) {
                picturesFile.mkdir()
            }
            val mediaDir = File(picturesFile, outPutFolderName)

            if (!mediaDir.exists()) {
                mediaDir.mkdirs()
            }
            val outputFile = File(mediaDir, displayName)
            outputFile.outputStream().use { fileOutputStream ->
                mutableBitmap.compress(
                    outputFormat.toCompressFormat(),
                    compressLevel,
                    fileOutputStream,
                )
            }
            val outputUri = FileProvider.getUriForFile(
                MyApp.instance,
                "${BuildConfig.APPLICATION_ID}.fileprovider",
                outputFile,
            )
            MediaScannerConnection.scanFile(
                MyApp.instance,
                arrayOf(outputFile.absolutePath),
                null,
                null,
            )
            Result.success(outputUri.toMediaRef())
        }
    }
}
