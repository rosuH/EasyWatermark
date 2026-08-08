package me.rosuh.easywatermark.ui

import me.rosuh.easywatermark.data.model.ImageInfo
import me.rosuh.easywatermark.data.model.MediaRef
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Presentation-only progressive-import rules.  Session owns only the Ready ImageInfo values;
 * these tests deliberately exercise the user-visible slot seam rather than a Host implementation.
 */
class EditorMediaSlotTest {

    @Test
    fun freshPick_publishesEveryPendingSlotImmediately_andFirstReadyTakesFocusAfterFirstFailure() {
        val state = EditorMediaSlotState.start(
            importIds = listOf("first", "second", "third"),
            appendTo = emptyList(),
            focusedImportId = null,
        )

        assertEquals(listOf("first", "second", "third"), state.slots.map { it.importId })
        assertEquals("first", state.focusedImportId)

        val failed = state.markFailed("first", "iCloud unavailable")
        assertIs<EditorMediaSlot.Failed>(failed.slots.first())
        assertEquals("first", failed.focusedImportId, "focus waits for the first usable Ready slot")

        val ready = failed.markReady("second", image("/tmp/second"))
        assertEquals("second", ready.focusedImportId)
        assertEquals(listOf("/tmp/second"), ready.readyImagesInOrder().map { it.uri.value })
    }

    @Test
    fun addMore_appendsOnce_preservesFocusedReady_andRetryReturnsFailedSlotToPending() {
        val existing = listOf(
            EditorMediaSlot.Ready("old", image("/tmp/old")),
        )
        val started = EditorMediaSlotState.start(
            importIds = listOf("new-a", "new-b"),
            appendTo = existing,
            focusedImportId = "old",
        )
        assertEquals(listOf("old", "new-a", "new-b"), started.slots.map { it.importId })
        assertEquals("old", started.focusedImportId)

        val retriable = started.markFailed("new-a", "transfer failed")
            .markPendingRetry("new-a", nowMs = 1_000L)
        val pending = assertIs<EditorMediaSlot.Pending>(retriable.slot("new-a"))
        assertEquals(1_000L, pending.attemptStartedAtMs, "retry must restart Pending attempt clock")
        assertEquals("old", retriable.focusedImportId)
    }

    @Test
    fun pendingAttemptClock_isStateOwned_notComposableLifetime() {
        val started = EditorMediaSlotState.start(
            importIds = listOf("a"),
            appendTo = emptyList(),
            focusedImportId = null,
            nowMs = 100L,
        )
        val p0 = assertIs<EditorMediaSlot.Pending>(started.slot("a"))
        assertEquals(100L, p0.attemptStartedAtMs)
        val retried = started.markFailed("a", "x").markPendingRetry("a", nowMs = 500L)
        val p1 = assertIs<EditorMediaSlot.Pending>(retried.slot("a"))
        assertEquals(500L, p1.attemptStartedAtMs)
        assertTrue(p1.attemptId >= 1L)
    }

    @Test
    fun removeMovesFocusToNextReady_andAllowsEmptyReadySelection() {
        val state = EditorMediaSlotState(
            slots = listOf(
                EditorMediaSlot.Ready("a", image("/tmp/a")),
                EditorMediaSlot.Pending("b"),
                EditorMediaSlot.Ready("c", image("/tmp/c")),
            ),
            focusedImportId = "a",
        )

        val afterFirst = state.remove("a")
        assertEquals("c", afterFirst.focusedImportId)
        assertEquals(listOf("/tmp/c"), afterFirst.readyImagesInOrder().map { it.uri.value })

        val afterLast = afterFirst.remove("c")
        assertNull(afterLast.focusedImportId)
        assertEquals(emptyList(), afterLast.readyImagesInOrder())
        assertEquals(listOf("b"), afterLast.slots.map { it.importId })
    }

    private fun image(path: String) = ImageInfo(MediaRef(path), width = 100, height = 80)
}
