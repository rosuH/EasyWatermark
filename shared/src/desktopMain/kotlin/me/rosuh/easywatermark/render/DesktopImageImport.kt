package me.rosuh.easywatermark.render

import me.rosuh.easywatermark.data.model.ImageInfo
import me.rosuh.easywatermark.data.model.MediaRef
import me.rosuh.easywatermark.ui.Image
import java.io.File

/**
 * Pure Desktop import helpers (Open / Add more / Drop).
 *
 * Import is **selection only**: builds [ImageInfo] / gallery rows for Session.
 * It never chooses output paths or starts export jobs — those are Save / Export only.
 */
object DesktopImageImport {

    fun toImageInfos(files: List<File>): List<ImageInfo> =
        files.map { ImageInfo(MediaRef(it.absolutePath)) }

    fun toGalleryImages(files: List<File>, idOffset: Int = 0): List<Image> =
        files.mapIndexed { i, f ->
            Image(
                id = idOffset + i,
                uri = MediaRef(f.absolutePath),
                name = f.name,
                size = f.length(),
                date = f.lastModified(),
                check = true,
            )
        }

    /**
     * [append]=false replaces the selection (Launch Open).
     * [append]=true keeps existing order and appends new paths not already present (Add more / Drop in editor).
     */
    fun mergeSelection(
        existing: List<ImageInfo>,
        incoming: List<ImageInfo>,
        append: Boolean,
    ): List<ImageInfo> {
        if (!append || existing.isEmpty()) return incoming
        val seen = existing.map { it.uri.value }.toMutableSet()
        val out = existing.toMutableList()
        for (info in incoming) {
            if (seen.add(info.uri.value)) out.add(info)
        }
        return out
    }

    /**
     * Preview writes must never become the share-substitute "last real save".
     * Pure path identity check (no IO).
     */
    fun mayUpdateLastSavedFile(output: File, previewFile: File): Boolean =
        output.absoluteFile != previewFile.absoluteFile
}
