package me.rosuh.easywatermark.ui

import kotlinx.coroutines.runBlocking
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.getString

/**
 * Non-Compose access to product [StringResource]s (Toast, Intents, DataStore defaults).
 * Prefer [stringResource] inside Composables; use this only outside composition.
 */
fun sharedString(resource: StringResource): String =
    runBlocking { getString(resource) }
