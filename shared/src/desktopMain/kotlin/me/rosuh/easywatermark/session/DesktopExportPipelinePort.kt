package me.rosuh.easywatermark.session

import me.rosuh.easywatermark.data.model.ImageInfo
import me.rosuh.easywatermark.data.model.MediaRef
import me.rosuh.easywatermark.data.model.Result
import me.rosuh.easywatermark.data.model.UserPreferences
import me.rosuh.easywatermark.data.model.WaterMark
import me.rosuh.easywatermark.render.DesktopRenderSaveSpine
import me.rosuh.easywatermark.render.DesktopSaveDecision
import java.io.File

/**
 * Desktop [ExportPipelinePort]: validates a source file [MediaRef], chooses a **unique** output
 * under [outputDirProvider], and delegates render/write to [DesktopRenderSaveSpine].
 *
 * Unique naming is an export destination policy (not the spine). Shared [Result] mapping and
 * width/height mutation on [ImageInfo] stay here until P3 outcome redesign.
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
            val outDir = outputDirProvider()
            outDir.mkdirs()
            val target = DesktopSaveDecision.resolveUniqueOutputFile(outDir, prefs.outputFormat)
            val saved = DesktopRenderSaveSpine.renderAndSave(
                imageBytes = bytes,
                config = config,
                prefs = prefs,
                target = target,
            )
            imageInfo.width = saved.width
            imageInfo.height = saved.height
            Result.success(saved.output)
        } catch (e: Exception) {
            Result.failure(
                null,
                code = ExportErrorCodes.FILE_NOT_FOUND,
                message = e.message ?: "Desktop export failed",
            )
        }
    }
}
