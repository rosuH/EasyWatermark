package me.rosuh.easywatermark.session

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
    fun renderAndSaveExact(
        imageBytes: ByteArray,
        request: DesktopRenderRequest,
        userChosen: File,
    ): DesktopSavedImage =
        DesktopRenderSaveSpine.renderAndSave(
            imageBytes = imageBytes,
            request = request,
            target = userChosen,
        )
}
