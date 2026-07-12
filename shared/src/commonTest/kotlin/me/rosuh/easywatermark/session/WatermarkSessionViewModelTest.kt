package me.rosuh.easywatermark.session

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Phase 1: pure session transitions (no DataStore). ViewModel construct needs a real repo —
 * covered on Android/Desktop integration; reducer is the unit gate here.
 */
class WatermarkSessionViewModelTest {
    @Test
    fun enterEditor_requiresNonEmptySelection() {
        val empty = reduceSessionUi(
            SessionUiSnapshot(),
            AppIntent.EnterEditor(selected = emptyList()),
        )
        assertTrue(empty.effects.isEmpty())
        assertEquals(
            me.rosuh.easywatermark.ui.LaunchScreenUiState.Launch,
            empty.snapshot.launch.uiState,
        )
    }
}
