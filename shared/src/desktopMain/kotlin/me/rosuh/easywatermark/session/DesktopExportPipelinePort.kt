package me.rosuh.easywatermark.session

import me.rosuh.easywatermark.data.model.ImageInfo
import me.rosuh.easywatermark.data.model.MediaRef
import me.rosuh.easywatermark.data.model.Result
import me.rosuh.easywatermark.data.model.UserPreferences
import me.rosuh.easywatermark.data.model.WaterMark
import me.rosuh.easywatermark.render.DesktopRenderPlan
import me.rosuh.easywatermark.render.DesktopSaveDecision
import me.rosuh.easywatermark.render.DesktopWatermarkComposer
import java.io.File

/**
 * Desktop [ExportPipelinePort] (ADR-0017 Phase 3): Skiko compose over a file [MediaRef]
 * (same spine as [me.rosuh.easywatermark.desktop.DesktopWatermarkFlow.runSaveFlow] render branch).
 *
 * [MediaRef.value] must be an absolute filesystem path to a readable image.
 * Writes a unique file under [outputDirProvider] using [DesktopSaveDecision.resolveUniqueOutputFile]
 * unless the caller later adds an explicit destination (Phase 3 keeps unique default-dir saves).
 */
class DesktopExportPipelinePort(
    private val outputDirProvider: () -> File,
) : ExportPipelinePort {

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
            val source = File(path)
            if (!source.isFile) {
                return Result.failure(
                    null,
                    code = ExportErrorCodes.FILE_NOT_FOUND,
                    message = "Source not a file: $path",
                )
            }
            val bytes = source.readBytes()
            val composed = when (val plan = DesktopSaveDecision.renderPlan(config.markMode, config.iconUri.value)) {
                is DesktopRenderPlan.Icon -> {
                    val iconFile = File(plan.iconPath)
                    require(iconFile.isFile) {
                        "Image-mode icon file is missing or not a regular file: '${plan.iconPath}'"
                    }
                    DesktopWatermarkComposer.composeIconOverRealImage(
                        imageBytes = bytes,
                        iconBytes = iconFile.readBytes(),
                        tileMode = config.tileMode,
                        textSize = config.textSize,
                        degree = config.degree,
                        hGapPercent = config.hGap,
                        vGapPercent = config.vGap,
                        alpha = config.alpha / 255f,
                        format = prefs.outputFormat,
                        quality = prefs.compressLevel,
                    )
                }
                DesktopRenderPlan.Text -> DesktopWatermarkComposer.composeOverRealImage(
                    imageBytes = bytes,
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
                    format = prefs.outputFormat,
                    quality = prefs.compressLevel,
                )
            }
            imageInfo.width = composed.width
            imageInfo.height = composed.height
            val outDir = outputDirProvider()
            outDir.mkdirs()
            val target = DesktopSaveDecision.resolveUniqueOutputFile(outDir, prefs.outputFormat)
            target.writeBytes(composed.png)
            Result.success(MediaRef(target.absolutePath))
        } catch (e: Exception) {
            Result.failure(
                null,
                code = ExportErrorCodes.FILE_NOT_FOUND,
                message = e.message ?: "Desktop export failed",
            )
        }
    }
}
