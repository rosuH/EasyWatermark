package me.rosuh.easywatermark.session

import me.rosuh.easywatermark.data.model.UserPreferences
import me.rosuh.easywatermark.data.model.WaterMark
import me.rosuh.easywatermark.render.DesktopRenderSaveSpine
import me.rosuh.easywatermark.render.DesktopSavedImage
import me.rosuh.easywatermark.render.DesktopSaveDecision
import java.io.File

/**
 * Desktop **Save As** destination policy: the user-chosen [File] is the exact write target.
 *
 * Distinct from batch export [DesktopSaveDecision.resolveUniqueOutputFile] and from the
 * app-private preview temp path. Production [me.rosuh.easywatermark.desktop] Save As must
 * route through [exactTarget] / [renderAndSaveExact] so unique-naming cannot silently apply.
 */
object DesktopSaveAsDestination {

    /** Exact user path — never unique-renamed. */
    fun exactTarget(userChosen: File): File = userChosen

    /**
     * Render/write via [DesktopRenderSaveSpine] to the exact [userChosen] path.
     * @see exactTarget
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
            target = exactTarget(userChosen),
        )
}
