package me.rosuh.easywatermark.session

import me.rosuh.easywatermark.data.model.ExportedMedia
import me.rosuh.easywatermark.data.model.ImageInfo
import me.rosuh.easywatermark.data.model.UserPreferences
import me.rosuh.easywatermark.data.model.WaterMark
import me.rosuh.easywatermark.render.DesktopRenderRequest
import me.rosuh.easywatermark.render.DesktopRenderSaveSpine
import me.rosuh.easywatermark.render.DesktopSaveDecision
import java.io.File

/**
 * Desktop [ExportPipelinePort]: validates a source file, chooses a **unique** output under
 * [outputDirProvider], and delegates render/write to [DesktopRenderSaveSpine].
 *
 * Freezes path, config, prefs, and [ImageInfo.offsetX]/[ImageInfo.offsetY] into
 * [DesktopRenderRequest] **before** source/destination IO (C2 review-fix). Unique naming is an
 * export destination policy (not the spine). Returns typed [ExportOutcome] (D1); may still
 * mutate width/height for legacy UI.
 */
class DesktopExportPipelinePort(
    private val outputDirProvider: () -> File,
) : ExportPipelinePort {

    override suspend fun exportOne(
        imageInfo: ImageInfo,
        config: WaterMark,
        prefs: UserPreferences,
    ): ExportOutcome {
        return try {
            // Snapshot all request identity before any filesystem IO (C2 review-fix).
            val path = imageInfo.uri.value
            val request = DesktopRenderRequest(
                config = config,
                prefs = prefs,
                offsetX = imageInfo.offsetX,
                offsetY = imageInfo.offsetY,
            )
            if (path.isBlank()) {
                return ExportOutcome.failure(
                    ExportFailure.SourceDecode(message = "Empty image path"),
                )
            }
            val source = File(path)
            if (!source.isFile) {
                return ExportOutcome.failure(
                    ExportFailure.SourceDecode(message = "Source not a file: $path"),
                )
            }
            val bytes = source.readBytes()
            val outDir = outputDirProvider()
            outDir.mkdirs()
            val target = DesktopSaveDecision.resolveUniqueOutputFile(outDir, prefs.outputFormat)
            val saved = DesktopRenderSaveSpine.renderAndSave(
                imageBytes = bytes,
                request = request,
                target = target,
            )
            // Legacy UI dims; success identity is ExportedMedia.
            imageInfo.width = saved.width
            imageInfo.height = saved.height
            ExportOutcome.success(
                ExportedMedia(
                    ref = saved.output,
                    width = saved.width,
                    height = saved.height,
                    format = saved.format,
                    byteCount = saved.outputByteCount.toLong(),
                ),
            )
        } catch (e: IllegalArgumentException) {
            // Empty icon plan / missing icon file from spine require() — render-path failure.
            ExportOutcome.failure(
                ExportFailure.Render(message = e.message ?: "Desktop render failed"),
            )
        } catch (e: Exception) {
            ExportOutcome.failure(
                ExportFailure.Io(message = e.message ?: "Desktop export failed"),
            )
        }
    }
}
