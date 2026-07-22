package me.rosuh.easywatermark.ui

import androidx.compose.ui.Modifier

/**
 * Public desktopMain-only forwarder over the internal common CLAMP preview drag modifier.
 *
 * Module bridge for `:desktopApp` (cannot see `:shared` `internal`). Owns no state, geometry,
 * Session/repository access, or preview invalidation — host callback does that.
 */
fun Modifier.desktopClampPreviewOffsetDrag(
    enabled: Boolean,
    selectionId: String,
    isClamp: Boolean,
    imageWidth: Float,
    imageHeight: Float,
    offsetX: Float,
    offsetY: Float,
    onOffsetCommit: (offsetX: Float, offsetY: Float) -> Unit,
): Modifier = clampPreviewOffsetDrag(
    enabled = enabled,
    selectionId = selectionId,
    isClamp = isClamp,
    imageWidth = imageWidth,
    imageHeight = imageHeight,
    offsetX = offsetX,
    offsetY = offsetY,
    onOffsetCommit = onOffsetCommit,
)
