package me.rosuh.easywatermark.ui

import java.io.File
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Structural parity gate for the progressive filmstrip (supplement only).
 * Behavior is proven by [EditorFilmstripMetricsTest] against production decision helpers.
 */
class EditorFilmstripParityContractTest {

    private fun readUi(name: String): String {
        val roots = listOf(
            // Running from repo root or :shared project dir.
            File("shared/src/commonMain/kotlin/me/rosuh/easywatermark/ui/$name"),
            File("src/commonMain/kotlin/me/rosuh/easywatermark/ui/$name"),
        )
        val hit = roots.firstOrNull { it.isFile }
            ?: error("missing UI source $name under ${File(".").absolutePath}")
        return hit.readText()
    }

    @Test
    fun progressivePath_reusesLegacyScaffoldContract() {
        val progressive = readUi("EditorProgressivePhotoStrip.kt")
        val photoStrip = readUi("EditorPhotoStrip.kt")
        val screen = readUi("EditorScreen.kt")

        // Progressive entry may remain, but must not own a second LazyRow geometry.
        assertFalse(
            progressive.contains("LazyRow(") && progressive.contains("height(88.dp)"),
            "progressive strip still owns divergent 88dp LazyRow",
        )
        assertFalse(
            progressive.contains(".width(72.dp)"),
            "progressive strip still uses 72dp item width",
        )
        assertFalse(
            progressive.contains(".size(44.dp)"),
            "progressive strip still uses 44dp cells",
        )

        // Legacy oracle keeps the interaction spine.
        assertTrue(photoStrip.contains("rememberSnapFlingBehavior"), "oracle missing snap fling")
        assertTrue(photoStrip.contains("snapshotFlow"), "oracle missing settle snapshotFlow")
        assertTrue(photoStrip.contains("PaddingValues("), "oracle missing centered padding")
        assertTrue(
            photoStrip.contains("height(56.dp)") || photoStrip.contains("RailHeight"),
            "oracle missing 56dp rail",
        )
        assertTrue(
            photoStrip.contains("size(40.dp)") || photoStrip.contains("ContentSize"),
            "oracle missing 40dp content",
        )
        assertTrue(
            photoStrip.contains("1.5.dp") || photoStrip.contains("FrameBorder"),
            "oracle missing 1.5dp border",
        )
        assertTrue(
            photoStrip.contains("RoundedCornerShape(2.dp)") || photoStrip.contains("FrameRadius"),
            "oracle missing r=2",
        )
        // Legacy no-long-press path stays on clickable (not combinedClickable-only).
        assertTrue(photoStrip.contains("Modifier.clickable"), "oracle lost legacy clickable path")
        assertFalse(
            photoStrip.contains("onItemLongPress"),
            "scaffold must not own progressive long-press remove",
        )
        assertTrue(
            photoStrip.contains("EditorFilmstripInteraction"),
            "scaffold must route through production interaction decisions",
        )

        // Progressive reuses scaffold. Ready is image-only; recovery UI is Pending/Failed only.
        assertTrue(
            progressive.contains("EditorFilmstripScaffold("),
            "progressive path does not reuse EditorFilmstripScaffold",
        )
        assertTrue(
            progressive.contains("is EditorMediaSlot.Ready"),
            "progressive missing Ready branch",
        )
        assertTrue(
            progressive.contains("CustomAccessibilityAction"),
            "progressive missing named accessibility actions on recovery cells",
        )
        assertTrue(
            progressive.contains("CircularProgressIndicator"),
            "progressive Pending missing loading animation",
        )
        // Ready must not paint a persistent remove/close overlay (parity regression).
        val readyBlock = progressive
            .substringAfter("is EditorMediaSlot.Ready")
            .substringBefore("is EditorMediaSlot.Pending")
        assertFalse(
            readyBlock.contains("RemoveChip") || readyBlock.contains("progressiveImportRemove"),
            "Ready cells still draw a persistent remove control",
        )
        // Pending must not show text preparing label or visible remove chip (owner 2026-08-07).
        val pendingBlock = progressive
            .substringAfter("is EditorMediaSlot.Pending")
            .substringBefore("is EditorMediaSlot.Failed")
        assertFalse(
            pendingBlock.contains("preparingLabel") || pendingBlock.contains("ios_import_preparing"),
            "Pending still shows preparing/preview text label",
        )
        assertFalse(
            pendingBlock.contains("PendingFailedRemoveChip") || pendingBlock.contains("progressiveImportRemove"),
            "Pending still draws a visible remove chip",
        )
        // Failed keeps visible remove recovery.
        assertTrue(
            progressive.contains("PendingFailedRemoveChip") ||
                progressive.substringAfter("is EditorMediaSlot.Failed").contains("progressiveImportRemove"),
            "Failed missing remove affordance",
        )
        assertFalse(
            progressive.contains("onLongClick"),
            "progressive still uses long-press-only remove",
        )
        assertFalse(
            progressive.contains("if (isFocused) 1.5.dp else 1.dp"),
            "progressive still paints item-local focus borders",
        )

        // Screen still has a filmstrip branch (progressive or legacy).
        assertTrue(
            screen.contains("EditorPhotoStrip(") || screen.contains("EditorProgressivePhotoStrip("),
            "EditorScreen lost filmstrip call site",
        )
    }
}
