package me.rosuh.easywatermark.session

import android.content.ContentResolver
import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import me.rosuh.easywatermark.BuildConfig
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
 * Pre-Q file path. ADR-0018 production path (always common; no rollout flag).
 *
 * D1: typed [ExportOutcome] with [ExportedMedia] facts.
 * D3: transactional persistence — Q+ pending rows deleted on failure; pre-Q temp+rename so
 * failed writes never leave a scannable final name; success only after publish/rename.
 */
class AndroidExportPipelinePort(
    private val appContext: Context,
    private val contentResolver: ContentResolver = appContext.contentResolver,
    /**
     * Injectable encode / FD open for unit tests (A1/A2). Production uses [PersistenceHooks.Default].
     */
    private val hooks: PersistenceHooks = PersistenceHooks.Default,
) : ExportPipelinePort {

    /**
     * Test/production hooks around the encode/write edge only (not a second persistence stack).
     */
    data class PersistenceHooks(
        val compress: (
            bitmap: Bitmap,
            format: Bitmap.CompressFormat,
            quality: Int,
            out: OutputStream,
        ) -> Boolean = { bitmap, format, quality, out ->
            bitmap.compress(format, quality, out)
        },
        val openWriteDescriptor: (ContentResolver, Uri) -> ParcelFileDescriptor? = { cr, uri ->
            cr.openFileDescriptor(uri, "w", null)
        },
    ) {
        companion object {
            val Default = PersistenceHooks()
        }
    }

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
        // Official getMemoryInfo() guidance: free reconstructable caches before a full-res decode.
        // Never skip the export — only drop BitmapCache / Coil / preview frames if the system
        // or Java heap is already tight.
        me.rosuh.easywatermark.platform.AndroidMemoryPressure.releaseReconstructableIfNeeded(appContext)
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
            // Failure path: still release owned source (not from BitmapCache).
            recycleOwnedQuietly(sourceBitmap)
            return ExportOutcome.failure(
                ExportFailure.Render(message = e.message ?: "compose failed"),
            )
        }

        // H2: source is decode-owned (decodeBitmapFromUri is not BitmapCache-backed).
        // Release as soon as compose has painted into [mutableBitmap] — drops peak of
        // source+composed(+icon) concurrent retention.
        // Icon may be BitmapCache-owned via decodeSampledBitmapFromResource — never recycle.
        recycleOwnedQuietly(sourceBitmap)
        AndroidExportMemoryProbe.onSourceReleasedAfterCompose()

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
                persistQPlus(encodeBitmap, outputFormat, compressLevel, displayName)
            } else {
                persistPreQ(encodeBitmap, outputFormat, compressLevel, displayName)
            }
        } finally {
            if (ownsEncodeBitmap && !encodeBitmap.isRecycled) {
                encodeBitmap.recycle()
            }
            // H2: composed export buffer is fully owned by this stack — free after encode/write.
            recycleOwnedQuietly(mutableBitmap)
            AndroidExportMemoryProbe.onComposedReleasedAfterEncode()
        }
    }

    /** Recycle only when still alive; never throws (export cleanup). */
    private fun recycleOwnedQuietly(bitmap: Bitmap?) {
        if (bitmap != null && !bitmap.isRecycled) {
            bitmap.recycle()
        }
    }

    /**
     * API 29+: insert pending row → write → publish (`IS_PENDING=0`).
     * Any failure/cancel after insert deletes the pending URI (D3).
     */
    private fun persistQPlus(
        encodeBitmap: Bitmap,
        outputFormat: ImageFormat,
        compressLevel: Int,
        displayName: String,
    ): ExportOutcome {
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

        var published = false
        try {
            val pfd = hooks.openWriteDescriptor(contentResolver, imageContentUri)
            if (pfd == null) {
                return ExportOutcome.failure(
                    ExportFailure.Persistence(message = "openFileDescriptor returned null"),
                )
            }
            val byteCount = pfd.use { descriptor ->
                val counting = CountingOutputStream(FileOutputStream(descriptor.fileDescriptor))
                val ok = hooks.compress(
                    encodeBitmap,
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

            val publishValues = ContentValues().apply {
                put(MediaStore.Images.Media.IS_PENDING, 0)
            }
            contentResolver.update(imageContentUri, publishValues, null, null)
            published = true
            return ExportOutcome.success(
                ExportedMedia(
                    ref = imageContentUri.toMediaRef(),
                    width = encodeBitmap.width,
                    height = encodeBitmap.height,
                    format = outputFormat,
                    byteCount = byteCount,
                ),
            )
        } catch (e: kotlinx.coroutines.CancellationException) {
            // D2/D3: rethrow after finally cleanup of pending row.
            throw e
        } catch (e: Exception) {
            return ExportOutcome.failure(
                ExportFailure.Io(message = e.message ?: "MediaStore write failed"),
            )
        } finally {
            if (!published) {
                runCatching { contentResolver.delete(imageContentUri, null, null) }
            }
        }
    }

    /**
     * API 23–28: write a hidden temp file, then rename to the final display name and scan.
     * Failed encode/write never leaves a scannable final path (D3).
     */
    private fun persistPreQ(
        encodeBitmap: Bitmap,
        outputFormat: ImageFormat,
        compressLevel: Int,
        displayName: String,
    ): ExportOutcome {
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

        // Temp name is not a product final; delete on any failure.
        val tempFile = File(mediaDir, ".$displayName.ewm_tmp")
        val outputFile = File(mediaDir, displayName)
        var committed = false
        try {
            if (tempFile.exists()) tempFile.delete()
            if (outputFile.exists()) outputFile.delete()

            val byteCount = tempFile.outputStream().use { fileOutputStream ->
                val counting = CountingOutputStream(fileOutputStream)
                val ok = hooks.compress(
                    encodeBitmap,
                    outputFormat.toCompressFormat(),
                    compressLevel,
                    counting,
                )
                if (!ok) {
                    return ExportOutcome.failure(
                        ExportFailure.Encode(message = "Bitmap.compress returned false"),
                    )
                }
                counting.flush()
                counting.count
            }

            // Atomic enough for same-directory rename on local filesystem.
            if (!tempFile.renameTo(outputFile)) {
                // Fallback: copy then delete temp.
                tempFile.inputStream().use { input ->
                    outputFile.outputStream().use { output -> input.copyTo(output) }
                }
                tempFile.delete()
            }
            if (!outputFile.exists() || outputFile.length() <= 0L) {
                return ExportOutcome.failure(
                    ExportFailure.Persistence(message = "rename/publish did not produce final file"),
                )
            }

            // File is already committed on disk; FileProvider may reject some Robolectric /
            // external-storage layouts — fall back to file URI so success still matches the file.
            val outputUri = try {
                FileProvider.getUriForFile(
                    appContext,
                    "${BuildConfig.APPLICATION_ID}.fileprovider",
                    outputFile,
                )
            } catch (_: IllegalArgumentException) {
                Uri.fromFile(outputFile)
            }
            MediaScannerConnection.scanFile(
                appContext,
                arrayOf(outputFile.absolutePath),
                arrayOf(outputFormat.toMediaStoreMimeType()),
                null,
            )
            committed = true
            return ExportOutcome.success(
                ExportedMedia(
                    ref = outputUri.toMediaRef(),
                    width = encodeBitmap.width,
                    height = encodeBitmap.height,
                    format = outputFormat,
                    byteCount = byteCount,
                ),
            )
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            return ExportOutcome.failure(
                ExportFailure.Io(message = e.message ?: "pre-Q write failed"),
            )
        } finally {
            if (!committed) {
                runCatching { if (tempFile.exists()) tempFile.delete() }
                // Never leave a half-written final name from a failed attempt.
                runCatching {
                    if (outputFile.exists() && outputFile.length() == 0L) outputFile.delete()
                }
            } else {
                runCatching { if (tempFile.exists()) tempFile.delete() }
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
