package me.rosuh.easywatermark.session

import me.rosuh.easywatermark.data.model.ImageInfo
import me.rosuh.easywatermark.data.model.MediaRef
import me.rosuh.easywatermark.data.model.Result
import me.rosuh.easywatermark.data.model.UserPreferences
import me.rosuh.easywatermark.data.model.WaterMark
import me.rosuh.easywatermark.data.model.WatermarkMode
import me.rosuh.easywatermark.data.repo.IosIconPersistence
import me.rosuh.easywatermark.render.IosByteArrayInterop
import me.rosuh.easywatermark.render.IosFinalRenderSpine
import me.rosuh.easywatermark.render.IosFontLoader
import me.rosuh.easywatermark.render.IosRenderRequest
import platform.Foundation.NSData
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSUUID
import platform.Foundation.dataWithContentsOfFile
import platform.Foundation.writeToFile

/**
 * iOS [ExportPipelinePort] (C3): full-resolution Final Export through [IosFinalRenderSpine] →
 * [CommonWatermarkPipeline], honoring [UserPreferences] format/quality and per-item offset.
 *
 * Snapshots path/config/prefs/offset **before** source/temp IO. Does not apply a preview max-edge
 * cap. [MediaRef.value] is a readable filesystem path; returns temp path under [NSTemporaryDirectory].
 *
 * Issue 22 §2.5: atomic write of encoded bytes precedes [ImageInfo] width/height mutation.
 */
class IosExportPipelinePort : ExportPipelinePort {

    /**
     * Test-only atomic-write override so a failed write can be forced without message parsing
     * or real filesystem failure. Production leaves this null and uses Foundation `writeToFile`.
     */
    internal var atomicWriteOverrideForTests: ((bytes: ByteArray, path: String) -> Boolean)? = null

    override suspend fun exportOne(
        imageInfo: ImageInfo,
        config: WaterMark,
        prefs: UserPreferences,
    ): Result<MediaRef> {
        return try {
            // Freeze identity before any filesystem IO (C3).
            val path = imageInfo.uri.value
            val request = IosRenderRequest(
                config = config,
                prefs = prefs,
                offsetX = imageInfo.offsetX,
                offsetY = imageInfo.offsetY,
            )
            if (path.isBlank()) {
                return Result.failure(null, code = "-1", message = "Empty image path")
            }
            val data: NSData = NSData.dataWithContentsOfFile(path)
                ?: return Result.failure(
                    null,
                    code = ExportErrorCodes.FILE_NOT_FOUND,
                    message = "Source not readable: $path",
                )
            val imageBytes = IosByteArrayInterop.fromNSData(data)
            val iconBytes: ByteArray? = if (config.markMode == WatermarkMode.Image) {
                IosIconPersistence.readIconBytes(config.iconUri)
            } else {
                null
            }
            val fontFamily = if (config.markMode == WatermarkMode.Text) {
                IosFontLoader.bundledFontFamily(latinFirst = true)
            } else {
                null
            }
            val encoded = IosFinalRenderSpine.renderAndEncode(
                imageBytes = imageBytes,
                request = request,
                iconBytes = iconBytes,
                fontFamily = fontFamily,
            )
            val ext = encoded.format.fileExtension
            val outPath = NSTemporaryDirectory() + "ewm_out_" + NSUUID().UUIDString + "." + ext
            // Issue 22 §2.5 steps 7–8: write first; mutate dimensions only after success.
            val ok = writeEncodedBytes(encoded.bytes, outPath)
            if (!ok) {
                return Result.failure(null, code = "-1", message = "Failed to write $outPath")
            }
            imageInfo.width = encoded.width
            imageInfo.height = encoded.height
            Result.success(MediaRef(outPath))
        } catch (e: Exception) {
            Result.failure(
                null,
                code = ExportErrorCodes.FILE_NOT_FOUND,
                message = e.message ?: "iOS export failed",
            )
        }
    }

    private fun writeEncodedBytes(bytes: ByteArray, path: String): Boolean {
        val override = atomicWriteOverrideForTests
        return if (override != null) {
            override(bytes, path)
        } else {
            IosByteArrayInterop.toNSData(bytes).writeToFile(path, atomically = true)
        }
    }
}
