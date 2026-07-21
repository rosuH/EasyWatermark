package me.rosuh.easywatermark.session

import android.content.ContentResolver
import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.media.MediaScannerConnection
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import me.rosuh.easywatermark.BuildConfig
import me.rosuh.easywatermark.MyApp
import me.rosuh.easywatermark.data.model.ImageFormat
import me.rosuh.easywatermark.data.model.ImageInfo
import me.rosuh.easywatermark.data.model.MediaRef
import me.rosuh.easywatermark.data.model.Result
import me.rosuh.easywatermark.data.model.UserPreferences
import me.rosuh.easywatermark.data.model.WaterMark
import me.rosuh.easywatermark.data.model.WatermarkMode
import me.rosuh.easywatermark.render.AndroidCommonRaster
import me.rosuh.easywatermark.utils.FileUtils.Companion.outPutFolderName
import me.rosuh.easywatermark.utils.bitmap.decodeBitmapFromUri
import me.rosuh.easywatermark.utils.bitmap.decodeSampledBitmapFromResource
import me.rosuh.easywatermark.utils.ktx.toCompressFormat
import me.rosuh.easywatermark.utils.ktx.toMediaRef
import me.rosuh.easywatermark.utils.ktx.toMediaStoreMimeType
import me.rosuh.easywatermark.utils.ktx.toUri
import java.io.File
import java.io.FileNotFoundException
import java.io.FileOutputStream

/**
 * Android [ExportPipelinePort]: decode → commonMain raster ([AndroidCommonRaster]) → MediaStore /
 * Pre-Q file path. ADR-0018 production path (always common; no rollout flag). */
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
        val sourceBitmap = rect.data?.bitmap
            ?: return Result.failure(
                null,
                code = "-1",
                message = "Copy bitmap from uri failed.",
            )

        imageInfo.width = sourceBitmap.width
        imageInfo.height = sourceBitmap.height

        val iconBitmap: Bitmap? = when (config.markMode) {
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
                iconBitmapRect.data!!.bitmap
            }
            WatermarkMode.Text -> null
        }
        val mutableBitmap = AndroidCommonRaster.composeToBitmap(
            context = appContext,
            background = sourceBitmap,
            config = config,
            imageInfo = imageInfo,
            icon = iconBitmap,
        )

        val outputFormat = prefs.outputFormat
        val compressLevel = prefs.compressLevel
        val fileExt = outputFormat.fileExtension
        val displayName = "ewm_${System.currentTimeMillis()}.$fileExt"
        // JPEG has no alpha: composite over opaque white before encode (Q+ and pre-Q).
        // PNG keeps premultiplied alpha as-is. Temporary JPEG flatten is recycled in finally.
        val encodeBitmap = bitmapForEncode(mutableBitmap, outputFormat)
        val ownsEncodeBitmap = encodeBitmap !== mutableBitmap
        try {
            return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val imageCollection =
                    MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
                val imageDetail = ContentValues().apply {
                    put(MediaStore.Images.Media.DISPLAY_NAME, displayName)
                    put(MediaStore.Images.Media.MIME_TYPE, outputFormat.toMediaStoreMimeType())
                    put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/$outPutFolderName/")
                    put(MediaStore.Images.Media.IS_PENDING, 1)
                }

                val imageContentUri = contentResolver.insert(imageCollection, imageDetail)
                contentResolver.openFileDescriptor(imageContentUri!!, "w", null).use { pfd ->
                    encodeBitmap.compress(
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
                    encodeBitmap.compress(
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
        } finally {
            if (ownsEncodeBitmap && !encodeBitmap.isRecycled) {
                encodeBitmap.recycle()
            }
        }
    }

    /**
     * JPEG cannot retain alpha. Composite [source] over opaque white so transparent pixels
     * decode near-white, never black. PNG returns [source] unchanged (caller must not recycle it
     * via the JPEG temp path).
     */
    private fun bitmapForEncode(source: Bitmap, format: ImageFormat): Bitmap {
        if (format != ImageFormat.JPEG) return source
        val flat = Bitmap.createBitmap(source.width, source.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(flat)
        canvas.drawColor(Color.WHITE)
        canvas.drawBitmap(source, 0f, 0f, null)
        return flat
    }
}
