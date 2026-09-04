package me.rosuh.easywatermark.ui

import kotlinx.serialization.Serializable

/**
 * Legacy typed Navigation-Compose destinations (plan D3 / C1.2).
 *
 * Product Launch / Editor / About now use [ProductShellNav] + [ProductShellHost]
 * (Launch↔Editor AnimatedContent; About overlays the live base). These
 * `@Serializable` route objects are retained only as historical symbols; do not
 * wire new product navigation through Navigation Compose. */
@Serializable
object LaunchRoute

@Serializable
object GalleryDialogRoute

@Serializable
object EditorRoute

@Serializable
object AboutRoute

@Serializable
object OpenSourceRoute
