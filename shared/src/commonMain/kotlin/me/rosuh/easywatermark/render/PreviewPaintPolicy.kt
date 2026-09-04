package me.rosuh.easywatermark.render

import kotlin.experimental.ExperimentalObjCRefinement
import kotlin.native.HiddenFromObjC

/**
 * When to replace the on-screen preview with an unwatermarked [PreviewPurpose.SourcePlaceholder]
 * while a new Watermarked frame is composing.
 *
 * Style / slider ticks keep the last Watermarked frame (stale-while-revalidate). Showing Source
 * on the same path makes the watermark vanish and pop back — the Desktop host bug.
 * Source is only a first-paint stand-in when the displayed photo is a different owned path.
 */
@OptIn(ExperimentalObjCRefinement::class)
@HiddenFromObjC
object PreviewPaintPolicy {
    fun showSourceWhileComposing(
        displayedOwnedPath: String?,
        requestOwnedPath: String,
    ): Boolean = displayedOwnedPath != requestOwnedPath
}
