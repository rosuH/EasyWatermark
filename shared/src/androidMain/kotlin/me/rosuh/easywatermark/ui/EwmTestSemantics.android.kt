package me.rosuh.easywatermark.ui

import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId

actual fun Modifier.ewmTestTagsAsResourceId(): Modifier =
    semantics { testTagsAsResourceId = true }
