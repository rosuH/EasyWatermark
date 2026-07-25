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
import me.rosuh.easywatermark.data.model.ExportedMedia
import me.rosuh.easywatermark.data.model.ImageFormat
import me.rosuh.easywatermark.data.model.ImageInfo
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
import java.io.OutputStream

/**
 * Android [ExportPipelinePort]: decode → commonMain raster ([AndroidCommonRaster]) → MediaStore /
 * Pre-Q file path. ADR-0018 production path (always common; no rollout flag). D1: typed
 * [ExportOutcome] with [ExportedMedia] facts.
 */
class AndroidExportPipelinePort(
    private val appContext: Context,
    private val contentResolver: ContentResolver = appContext.contentResolver,
) : ExportPipelinePort {

    override suspend fun exportOne(
        imageInfo: ImageInfo,
        config: WaterMark,
        prefs: UserPreferences,
    ): ExportOutcome = withContext(Dispatchers.IO) {
        try {
            exportOneInternal(imageInfo, config, prefs)
        } catch (_: FileNotFoundException) {
            ExportOutcome.failure(ExportFailure.SourceDecode())
        } catch (e: OutOfMemoryError) {
            ExportOutcome.failure(ExportFailure.Io.outOfMemory(e.message))
        }
    }

    private suspend fun exportOneInternal(
        imageInfo: ImageInfo,
        config: WaterMark,
        prefs: UserPreferences,
    ): ExportOutcome {
        val rect = decodeBitmapFromUri(contentResolver, imageInfo.uri.toUri())
        if (rect.isFailure()) {
            return ExportOutcome.failure(
                ExportFailure.SourceDecode(message = rect.message),
            )
        }
        val sourceBitmap = rect.data?.bitmap
            ?: return ExportOutcome.failure(
                ExportFailure.SourceDecode(message = "Copy bitmap from uri failed."),
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
                    return ExportOutcome.failure(
                        ExportFailure.Render(
                            message = "decodeSampledBitmapFromResource == null",
                        ),
                    )
                }
                iconBitmapRect.data!!.bitmap
            }
            WatermarkMode.Text -> null
        }
        val mutableBitmap = try {
            AndroidCommonRaster.composeToBitmap(
                context = appContext,
                background = sourceBitmap,
                config = config,
                imageInfo = imageInfo,
                icon = iconBitmap,
            )
        } catch (e: Exception) {
            return ExportOutcome.failure(
                ExportFailure.Render(message = e.message ?: "compose failed"),
            )
        }

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
                    ?: return ExportOutcome.failure(
                        ExportFailure.Persistence(message = "MediaStore insert returned null"),
                    )
                val byteCount = contentResolver.openFileDescriptor(imageContentUri, "w", null).use { pfd ->
                    if (pfd == null) {
                        return ExportOutcome.failure(
                            ExportFailure.Persistence(message = "openFileDescriptor returned null"),
                        )
                    }
                    val counting = CountingOutputStream(FileOutputStream(pfd.fileDescriptor))
                    val ok = encodeBitmap.compress(
                        outputFormat.toCompressFormat(),
                        compressLevel,
                        counting,
                    )
                    if (!ok) {
                        return ExportOutcome.failure(
                            ExportFailure.Encode(message = "Bitmap.compress returned false"),
                        )
                    }
                    counting.count
                }
                imageDetail.clear()
                imageDetail.put(MediaStore.Images.Media.IS_PENDING, 0)
                contentResolver.update(imageContentUri, imageDetail, null, null)
                ExportOutcome.success(
                    ExportedMedia(
                        ref = imageContentUri.toMediaRef(),
                        width = encodeBitmap.width,
                        height = encodeBitmap.height,
                        format = outputFormat,
                        byteCount = byteCount,
                    ),
                )
            } else {
                val picturesFile: File =
                    Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES)
                        ?: return ExportOutcome.failure(
                            ExportFailure.Io(message = "Can't get pictures directory."),
                        )
                if (!picturesFile.exists()) {
                    picturesFile.mkdir()
                }
                val mediaDir = File(picturesFile, outPutFolderName)

                if (!mediaDir.exists()) {
                    mediaDir.mkdirs()
                }
                val outputFile = File(mediaDir, displayName)
                val byteCount = outputFile.outputStream().use { fileOutputStream ->
                    val counting = CountingOutputStream(fileOutputStream)
                    val ok = encodeBitmap.compress(
                        outputFormat.toCompressFormat(),
                        compressLevel,
                        counting,
                    )
                    if (!ok) {
                        return ExportOutcome.failure(
                            ExportFailure.Encode(message = "Bitmap.compress returned false"),
                        )
                    }
                    counting.count
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
                ExportOutcome.success(
                    ExportedMedia(
                        ref = outputUri.toMediaRef(),
                        width = encodeBitmap.width,
                        height = encodeBitmap.height,
                        format = outputFormat,
                        byteCount = byteCount,
                    ),
                )
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

    /** Counts bytes written while delegating to [delegate] (D1 ExportedMedia.byteCount). */
    private class CountingOutputStream(
        private val delegate: OutputStream,
    ) : OutputStream() {
        var count: Long = 0
            private set

        override fun write(b: Int) {
            delegate.write(b)
            count++
        }

        override fun write(b: ByteArray, off: Int, len: Int) {
            delegate.write(b, off, len)
            count += len.toLong()
        }

        override fun flush() = delegate.flush()

        override fun close() = delegate.close()
    }
}
