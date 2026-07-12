package me.rosuh.easywatermark.session

import me.rosuh.easywatermark.data.model.ImageInfo
import me.rosuh.easywatermark.data.model.MediaRef
import me.rosuh.easywatermark.data.model.Result
import me.rosuh.easywatermark.data.model.UserPreferences
import me.rosuh.easywatermark.data.model.WaterMark
import me.rosuh.easywatermark.data.model.WatermarkMode
import me.rosuh.easywatermark.data.repo.IosIconPersistence
import me.rosuh.easywatermark.render.IosByteArrayInterop
import me.rosuh.easywatermark.render.IosWatermarkRenderBridge
import platform.Foundation.NSData
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSUUID
import platform.Foundation.dataWithContentsOfFile
import platform.Foundation.writeToFile

/**
 * iOS [ExportPipelinePort] (ADR-0017 Phase 4): Skiko render via [IosWatermarkRenderBridge]
 * (wrap of accepted iOS path — no algorithm rewrite).
 *
 * [MediaRef.value] must be a readable filesystem path to encoded image bytes.
 * Writes a PNG under [NSTemporaryDirectory] and returns that path as [MediaRef].
 */
class IosExportPipelinePort : ExportPipelinePort {

    override suspend fun exportOne(
        imageInfo: ImageInfo,
        config: WaterMark,
        prefs: UserPreferences,
    ): Result<MediaRef> {
        return try {
            val path = imageInfo.uri.value
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
            val rendered = when (config.markMode) {
                WatermarkMode.Text -> IosWatermarkRenderBridge.renderWatermarkedPng(
                    imageBytes = imageBytes,
                    text = config.text,
                    tileMode = config.tileMode,
                    textSize = config.textSize,
                    degree = config.degree,
                    hGapPercent = config.hGap,
                    vGapPercent = config.vGap,
                    alpha = config.alpha / 255f,
                    colorArgb = config.textColor,
                    typeface = config.textTypeface,
                    textStyle = config.textStyle,
                )
                WatermarkMode.Image -> {
                    val iconBytes = IosIconPersistence.readIconBytes(config.iconUri)
                    IosWatermarkRenderBridge.renderIconWatermarkedPng(
                        imageBytes = imageBytes,
                        iconBytes = iconBytes,
                        tileMode = config.tileMode,
                        textSize = config.textSize,
                        degree = config.degree,
                        hGapPercent = config.hGap,
                        vGapPercent = config.vGap,
                        alpha = config.alpha / 255f,
                    )
                }
            }
            imageInfo.width = rendered.width
            imageInfo.height = rendered.height
            // prefs.format currently ignored for iOS (always PNG via Skia encode) — matches product path.
            val outPath = NSTemporaryDirectory() + "ewm_out_" + NSUUID().UUIDString + ".png"
            val ok = IosByteArrayInterop.toNSData(rendered.png).writeToFile(outPath, atomically = true)
            if (!ok) {
                return Result.failure(null, code = "-1", message = "Failed to write $outPath")
            }
            Result.success(MediaRef(outPath))
        } catch (e: Exception) {
            Result.failure(
                null,
                code = ExportErrorCodes.FILE_NOT_FOUND,
                message = e.message ?: "iOS export failed",
            )
        }
    }
}
