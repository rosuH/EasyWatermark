package me.rosuh.easywatermark.session

import me.rosuh.easywatermark.data.model.ImageInfo
import me.rosuh.easywatermark.data.model.MediaRef
import me.rosuh.easywatermark.data.model.WaterMark
import me.rosuh.easywatermark.ui.Image
import java.io.File

/**
 * Desktop **import-only** selection seam used by Open / Add more / Drop.
 *
 * Builds Session [ImageInfo] lists and commits [AppIntent.EnterEditor].
 * Does **not** choose export destinations or call [ExportPipelinePort] / [WatermarkSessionViewModel.exportAndAwait].
 */
object DesktopSessionImport {

    fun imageInfosFromFiles(files: List<File>): List<ImageInfo> =
        files.map { ImageInfo(MediaRef(it.absolutePath)) }

    /**
     * [append]=false replaces the selection (Launch Open).
     * [append]=true keeps existing order and appends paths not already present (Add more / Drop).
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

    fun galleryRows(selected: List<ImageInfo>): List<Image> =
        selected.mapIndexed { i, info ->
            val f = File(info.uri.value)
            Image(
                id = i,
                uri = info.uri,
                name = f.name.ifEmpty { info.uri.value },
                size = if (f.isFile) f.length() else 0L,
                date = if (f.isFile) f.lastModified() else 0L,
                check = true,
            )
        }

    /**
     * Production import path: merge → [AppIntent.EnterEditor] only.
     * Callers must not start export after this for Open/Add/Drop.
     *
     * @return the committed selection (empty if [files] empty).
     */
    suspend fun commitImport(
        session: WatermarkSessionViewModel,
        files: List<File>,
        existingSelection: List<ImageInfo>,
        append: Boolean,
        waterMark: WaterMark,
    ): List<ImageInfo> {
        if (files.isEmpty()) return existingSelection
        val selected = mergeSelection(
            existingSelection,
            imageInfosFromFiles(files),
            append = append,
        )
        if (selected.isEmpty()) return selected
        session.dispatchAndAwait(
            AppIntent.EnterEditor(
                selected = selected,
                gallerySnapshot = galleryRows(selected),
                waterMark = waterMark,
            ),
        )
        return selected
    }
}
