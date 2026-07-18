package me.rosuh.easywatermark.ui

import kotlinx.serialization.Serializable

/**
 * Legacy typed Navigation-Compose destinations (plan D3 / C1.2).
 *
 * Product Launch / Editor / About now use [ProductShellNav] + [ProductShellHost] (shared
 * AnimatedContent transitions). These `@Serializable` route objects are retained only as
 * Historical symbols; do not wire new product navigation through Navigation Compose. */
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
