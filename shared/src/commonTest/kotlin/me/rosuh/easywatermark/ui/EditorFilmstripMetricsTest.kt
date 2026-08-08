package me.rosuh.easywatermark.ui

import me.rosuh.easywatermark.data.model.ImageInfo
import me.rosuh.easywatermark.data.model.MediaRef
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Geometry constants + production-used interaction decisions for the unified filmstrip scaffold.
 * Complements the source-structure [EditorFilmstripParityContractTest].
 */
class EditorFilmstripMetricsTest {

    @Test
    fun geometry_matchesLegacyOracle() {
        assertEquals(56f, EditorFilmstripMetrics.RailHeight.value)
        assertEquals(48f, EditorFilmstripMetrics.CellSize.value)
        assertEquals(40f, EditorFilmstripMetrics.ContentSize.value)
        assertEquals(8f, EditorFilmstripMetrics.ItemGap.value)
        assertEquals(1.5f, EditorFilmstripMetrics.FrameBorder.value)
        assertEquals(2f, EditorFilmstripMetrics.FrameRadius.value)
        assertEquals(56f, EditorFilmstripMetrics.Pitch.value, "pitch = 48 cell + 8 gap")
    }

    @Test
    fun canSelect_onlyReady() {
        assertFalse(EditorFilmstripInteraction.canSelectSlot(EditorMediaSlot.Pending("p")))
        assertTrue(
            EditorFilmstripInteraction.canSelectSlot(
                EditorMediaSlot.Ready("r", ImageInfo(MediaRef("/t"))),
            ),
        )
        assertFalse(EditorFilmstripInteraction.canSelectSlot(EditorMediaSlot.Failed("f", "e")))
    }

    @Test
    fun tap_selectsOnce_thenIgnoresSameKey() {
        assertTrue(
            EditorFilmstripInteraction.shouldPublishOnTap(
                canSelect = true,
                itemKey = "a",
                lastAppliedKey = null,
            ),
        )
        assertFalse(
            EditorFilmstripInteraction.shouldPublishOnTap(
                canSelect = true,
                itemKey = "a",
                lastAppliedKey = "a",
            ),
            "second tap on already-applied key must not re-publish",
        )
        assertFalse(
            EditorFilmstripInteraction.shouldPublishOnTap(
                canSelect = false,
                itemKey = "pending",
                lastAppliedKey = null,
            ),
            "Pending/Failed must never publish Session selection",
        )
    }

    @Test
    fun programmaticCenter_doesNotSettleSelect() {
        assertFalse(
            EditorFilmstripInteraction.shouldPublishOnSettle(
                wasUserScrolling = true,
                programmatic = true,
                canSelect = true,
                centeredKey = "b",
                lastAppliedKey = "a",
            ),
            "programmatic re-center after tap must not double-select",
        )
        assertFalse(
            EditorFilmstripInteraction.shouldPublishOnSettle(
                wasUserScrolling = false,
                programmatic = false,
                canSelect = true,
                centeredKey = "b",
                lastAppliedKey = "a",
            ),
            "idle layout without user scroll must not select",
        )
    }

    @Test
    fun userSettle_selectsOnlyReadyCenter() {
        assertTrue(
            EditorFilmstripInteraction.shouldPublishOnSettle(
                wasUserScrolling = true,
                programmatic = false,
                canSelect = true,
                centeredKey = "ready",
                lastAppliedKey = "other",
            ),
        )
        assertFalse(
            EditorFilmstripInteraction.shouldPublishOnSettle(
                wasUserScrolling = true,
                programmatic = false,
                canSelect = false,
                centeredKey = "pending",
                lastAppliedKey = "other",
            ),
            "settle on Pending/Failed must not mutate Session",
        )
        assertFalse(
            EditorFilmstripInteraction.shouldPublishOnSettle(
                wasUserScrolling = true,
                programmatic = false,
                canSelect = true,
                centeredKey = "ready",
                lastAppliedKey = "ready",
            ),
            "settle on already-applied key is a no-op",
        )
        assertFalse(
            EditorFilmstripInteraction.shouldPublishOnSettle(
                wasUserScrolling = true,
                programmatic = false,
                canSelect = true,
                centeredKey = null,
                lastAppliedKey = "a",
            ),
            "null center key must not publish",
        )
    }

    @Test
    fun listAppend_doesNotRequestRecenter() {
        // Effect key is selection only — growing the list keeps the same key.
        assertEquals("sel", EditorFilmstripInteraction.recenterEffectKey("sel"))
        assertEquals(
            EditorFilmstripInteraction.recenterEffectKey("sel"),
            EditorFilmstripInteraction.recenterEffectKey("sel"),
            "append must not change recenter effect key",
        )
        assertFalse(
            EditorFilmstripInteraction.shouldProgrammaticRecenter(
                selectedKey = "sel",
                lastAppliedKey = "sel",
                atCenter = true,
                userScrollInProgress = false,
            ),
            "already-centered selection after append must not yank scroll",
        )
        assertTrue(
            EditorFilmstripInteraction.shouldProgrammaticRecenter(
                selectedKey = "sel",
                lastAppliedKey = "sel",
                atCenter = false,
                userScrollInProgress = false,
            ),
            "external selection off-center still recenters",
        )
        assertFalse(
            EditorFilmstripInteraction.shouldProgrammaticRecenter(
                selectedKey = "sel",
                lastAppliedKey = "other",
                atCenter = false,
                userScrollInProgress = true,
            ),
            "never fight an in-progress user fling",
        )
    }
}
