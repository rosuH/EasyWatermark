package me.rosuh.easywatermark.session

import me.rosuh.easywatermark.data.model.UserPreferences
import me.rosuh.easywatermark.data.model.WaterMark
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
     * Production Save As write: exact path compose+encode+write.
     * [me.rosuh.easywatermark.desktop] Window must call this so tests cover the live caller.
     */
    fun renderAndSaveExact(
        imageBytes: ByteArray,
        config: WaterMark,
        prefs: UserPreferences,
        userChosen: File,
    ): DesktopSavedImage =
        DesktopRenderSaveSpine.renderAndSave(
            imageBytes = imageBytes,
            config = config,
            prefs = prefs,
            target = userChosen,
        )
}
