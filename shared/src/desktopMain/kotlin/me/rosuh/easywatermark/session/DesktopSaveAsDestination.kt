package me.rosuh.easywatermark.session

import me.rosuh.easywatermark.data.model.WatermarkMode
import me.rosuh.easywatermark.font.DesktopWatermarkFontAccess
import me.rosuh.easywatermark.font.FontResolution
import me.rosuh.easywatermark.render.DesktopRenderRequest
import me.rosuh.easywatermark.render.DesktopRenderSaveSpine
import me.rosuh.easywatermark.render.DesktopSavedImage
import java.io.File

/**
 * Desktop **Save As** exact-write seam used by the product window.
 *
 * Writes to the user-chosen [File] via [DesktopRenderSaveSpine] — never
 * [me.rosuh.easywatermark.render.DesktopSaveDecision.resolveUniqueOutputFile].
 */
object DesktopSaveAsDestination {

    /**
     * Production Save As write: exact path compose+encode+write with immutable [request]
     * (includes frozen per-item offset).
     */
    suspend fun renderAndSaveExact(
        imageBytes: ByteArray,
        request: DesktopRenderRequest,
        userChosen: File,
        fontAccess: DesktopWatermarkFontAccess = DesktopWatermarkFontAccess(),
    ): DesktopSavedImage {
        val fontFamily = if (request.config.markMode == WatermarkMode.Text) {
            when (val resolution = fontAccess.resolve(request.config.fontRef)) {
                is FontResolution.Success -> resolution.family
                is FontResolution.Failure -> throw IllegalArgumentException(resolution.reason)
            }
        } else {
            null
        }
        return DesktopRenderSaveSpine.renderAndSave(
            imageBytes = imageBytes,
            request = request,
            target = userChosen,
            fontFamily = fontFamily,
        )
    }
}
