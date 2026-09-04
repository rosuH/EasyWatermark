package me.rosuh.easywatermark.ui.theme

import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * I3 — EwmTheme formalizes Design* without inventing a new palette.
 */
class EwmTokensTest {

    @Test
    fun colors_matchDesignHandoff() {
        assertEquals(DesignBrand, EwmTheme.colors.brand)
        assertEquals(DesignEditorBg, EwmTheme.colors.editorBackground)
        assertEquals(DesignChipSelected, EwmTheme.colors.chipSelected)
        assertEquals(DesignExportPill, EwmTheme.colors.exportPill)
        assertEquals(DesignSliderTrack, EwmTheme.colors.sliderTrack)
        assertEquals(DesignNeutralMuted, EwmTheme.colors.neutralMuted)
    }

    @Test
    fun shapes_matchDesignRadii() {
        assertEquals(DesignRadiusSm, EwmTheme.shapes.chipRadiusPx)
        assertEquals(DesignRadiusTab, EwmTheme.shapes.tabRadiusPx)
        assertEquals(DesignRadiusSm.dp, EwmTheme.shapes.chipRadiusDp)
        assertEquals(DesignRadiusTab.dp, EwmTheme.shapes.tabRadiusDp)
    }

    @Test
    fun space_focusedNotEveryDp() {
        // Documented control band only — not a full spacing scale.
        assertTrue(EwmTheme.space.controlGap.value > 0f)
        assertTrue(EwmTheme.space.choiceChipMinWidth.value >= 48f)
        assertEquals(2f, EwmTheme.space.sliderTrackHeight.value)
        assertEquals(20f, EwmTheme.space.sliderThumbSize.value)
    }

    @Test
    fun state_errorPresent() {
        assertEquals(md_theme_dark_error, EwmTheme.state.error)
        assertEquals(md_theme_dark_tertiary, EwmTheme.state.exportWashTertiary)
    }
}
