package me.rosuh.easywatermark.ui

import androidx.compose.ui.Modifier

/**
 * Map [androidx.compose.ui.platform.testTag] to Android UIAutomator resource-ids.
 * iOS already exposes testTag as accessibilityIdentifier; Desktop has no UIAutomator.
 */
expect fun Modifier.ewmTestTagsAsResourceId(): Modifier
