package me.rosuh.easywatermark.ui

import me.rosuh.easywatermark.data.model.ImageInfo

/**
 * Transient editor-import presentation state.
 *
 * This is intentionally internal and UI-only: a Session is allowed to contain only the
 * [Ready.image] values.  In particular, neither [Pending] nor [Failed] invents a MediaRef that
 * an export could accidentally consume.
 */
internal sealed interface EditorMediaSlot {
    val importId: String

    data class Pending(
        override val importId: String,
        val retryCount: Int = 0,
        /**
         * Monotonic attempt counter. Bumped on every entry into Pending (fresh or retry) so UI
         * chrome timing restarts with the attempt, not the composable instance / LazyRow recycle.
         */
        val attemptId: Long = 0L,
        /** Wall-independent attempt start (ms on a shared clock provided by the host). */
        val attemptStartedAtMs: Long = 0L,
    ) : EditorMediaSlot

    data class Ready(
        override val importId: String,
        val image: ImageInfo,
    ) : EditorMediaSlot

    data class Failed(
        override val importId: String,
        val message: String,
        val retryCount: Int = 0,
        /** Last Pending attempt id so retry can bump without composable-owned clocks. */
        val attemptId: Long = 0L,
    ) : EditorMediaSlot
}

/**
 * Immutable rules for fixed-order progressive slots.  Hosts own delivery/IO; this type owns only
 * user-visible order and focus, which makes the failure and retry races testable without Photos.
 */
internal data class EditorMediaSlotState(
    val slots: List<EditorMediaSlot>,
    val focusedImportId: String?,
) {
    fun slot(importId: String): EditorMediaSlot? = slots.firstOrNull { it.importId == importId }

    fun readyImagesInOrder(): List<ImageInfo> = slots.mapNotNull { (it as? EditorMediaSlot.Ready)?.image }

    fun markReady(importId: String, image: ImageInfo): EditorMediaSlotState {
        val prior = slot(importId) ?: return this
        val replaced = slots.map { slot ->
            if (slot.importId == importId) EditorMediaSlot.Ready(importId, image) else slot
        }
        // A failed focus cannot paint a preview.  As soon as the next Ready item appears, focus it.
        val focus = when (val focused = focusedImportId?.let(::slot)) {
            null -> importId
            is EditorMediaSlot.Ready -> focused.importId
            is EditorMediaSlot.Pending -> focused.importId
            is EditorMediaSlot.Failed -> importId
        }
        return copy(slots = replaced, focusedImportId = focus)
    }

    fun markFailed(importId: String, message: String): EditorMediaSlotState {
        val prior = slot(importId) ?: return this
        val retryCount = when (prior) {
            is EditorMediaSlot.Pending -> prior.retryCount
            is EditorMediaSlot.Failed -> prior.retryCount
            is EditorMediaSlot.Ready -> 0
        }
        val attemptId = when (prior) {
            is EditorMediaSlot.Pending -> prior.attemptId
            is EditorMediaSlot.Failed -> prior.attemptId
            is EditorMediaSlot.Ready -> 0L
        }
        return copy(
            slots = slots.map { slot ->
                if (slot.importId == importId) {
                    EditorMediaSlot.Failed(importId, message, retryCount, attemptId)
                } else {
                    slot
                }
            },
        )
    }

    fun markPendingRetry(importId: String, nowMs: Long): EditorMediaSlotState {
        val prior = slot(importId) as? EditorMediaSlot.Failed ?: return this
        val nextAttempt = prior.attemptId + 1L
        return copy(
            slots = slots.map { slot ->
                if (slot.importId == importId) {
                    EditorMediaSlot.Pending(
                        importId = importId,
                        retryCount = prior.retryCount + 1,
                        attemptId = nextAttempt,
                        attemptStartedAtMs = nowMs,
                    )
                } else {
                    slot
                }
            },
        )
    }

    fun focusReady(importId: String): EditorMediaSlotState {
        if (slot(importId) !is EditorMediaSlot.Ready) return this
        return copy(focusedImportId = importId)
    }

    fun remove(importId: String): EditorMediaSlotState {
        val index = slots.indexOfFirst { it.importId == importId }
        if (index < 0) return this
        val remaining = slots.filterIndexed { i, _ -> i != index }
        val currentFocus = focusedImportId
        val focus = if (currentFocus != importId) {
            currentFocus?.takeIf { id -> remaining.any { it.importId == id } }
        } else {
            // Prefer the next Ready cell, then a preceding Ready cell. Pending/Failed cannot focus
            // a Session preview and intentionally yield null until a Ready value arrives.
            remaining.drop(index).firstOrNull { it is EditorMediaSlot.Ready }?.importId
                ?: remaining.take(index).lastOrNull { it is EditorMediaSlot.Ready }?.importId
        }
        return copy(slots = remaining, focusedImportId = focus)
    }

    companion object {
        fun start(
            importIds: List<String>,
            appendTo: List<EditorMediaSlot>,
            focusedImportId: String?,
            nowMs: Long = 0L,
        ): EditorMediaSlotState {
            val uniqueNew = importIds
                .filter { it.isNotBlank() }
                .distinct()
                .filter { candidate -> appendTo.none { it.importId == candidate } }
            val slots = appendTo + uniqueNew.map {
                EditorMediaSlot.Pending(
                    importId = it,
                    attemptId = 1L,
                    attemptStartedAtMs = nowMs,
                )
            }
            val existingFocus = focusedImportId?.takeIf { id -> slots.any { it.importId == id } }
            return EditorMediaSlotState(
                slots = slots,
                focusedImportId = existingFocus ?: slots.firstOrNull()?.importId,
            )
        }
    }
}
