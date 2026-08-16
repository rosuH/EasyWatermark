package me.rosuh.easywatermark.ui

import kotlinx.coroutines.CoroutineScope

/**
 * iOS host facade over the common [DraftRenderConflator].
 * J5: internal host helper — not part of the Swift product API surface.
 */
internal class IosDraftRenderConflator<T>(
    scope: CoroutineScope,
    render: suspend (T) -> Unit,
) : DraftRenderConflator<T>(scope, render)
