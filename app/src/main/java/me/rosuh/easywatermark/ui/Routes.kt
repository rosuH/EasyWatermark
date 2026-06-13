package me.rosuh.easywatermark.ui

import kotlinx.serialization.Serializable

/**
 * Typed Navigation-Compose destinations (CMP-readiness, plan D3 / C1.2).
 *
 * Replaces string routes with `@Serializable` objects so navigation is type-safe and
 * portable to the JetBrains `org.jetbrains.androidx.navigation` coordinate used in the
 * Compose Multiplatform phase (string routes + Parcelize nav args don't survive the move).
 * All destinations are currently argument-less; when a screen needs typed arguments,
 * convert its object to a `@Serializable data class` and read them with `toRoute<T>()`.
 */
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
