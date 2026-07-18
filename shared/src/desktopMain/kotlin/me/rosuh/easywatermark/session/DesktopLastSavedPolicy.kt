package me.rosuh.easywatermark.session

import java.io.File

/**
 * Whether an output path may become the Desktop share-substitute "last real save".
 * Preview temp must never qualify; only explicit Export / Save As outputs may.
 */
object DesktopLastSavedPolicy {

    fun mayTrackAsLastSaved(output: File, previewFile: File): Boolean =
        output.absoluteFile != previewFile.absoluteFile
}
