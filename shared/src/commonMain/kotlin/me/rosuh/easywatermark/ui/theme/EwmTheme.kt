package me.rosuh.easywatermark.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * I3 — focused EasyWatermark product tokens.
 *
 * Formalizes existing [DesignBrand] / radii / motion constants used by the editor shell.
 * **Does not** invent a full Material type scale or rewrite every dp call site.
 * [DesignBrand] et al. remain the source-of-truth vals; this groups them for discovery.
 *
 * Structure / semantics tests ≠ renderer goldens ≠ product UI perceptual goldens
 * (see evidence/i3).
 */
object EwmTheme {
    val colors: EwmColorTokens = EwmColorTokens
    val shapes: EwmShapeTokens = EwmShapeTokens
    val space: EwmSpaceTokens = EwmSpaceTokens
    val motion: EwmMotionTokens = EwmMotionTokens
    val state: EwmStateTokens = EwmStateTokens
}

/** Brand / surface colors from Figma handoff (preview_edit). */
object EwmColorTokens {
    val brand: Color get() = DesignBrand
    val editorBackground: Color get() = DesignEditorBg
    val chipSelected: Color get() = DesignChipSelected
    val exportPill: Color get() = DesignExportPill
    val sliderTrack: Color get() = DesignSliderTrack
    val neutralMuted: Color get() = DesignNeutralMuted
}

/** Focused radii — chip/tab only (not every corner in the app). */
object EwmShapeTokens {
    /** Hard UI radius (chips, filmstrip frame, export pill) — [DesignRadiusSm]. */
    val chipRadiusDp: Dp get() = DesignRadiusSm.dp

    /** Tab selected background radius — [DesignRadiusTab]. */
    val tabRadiusDp: Dp get() = DesignRadiusTab.dp

    val chipRadiusPx: Int get() = DesignRadiusSm
    val tabRadiusPx: Int get() = DesignRadiusTab
}

/**
 * Small spacing set for controls already on design rails.
 * Call sites may keep local values; these document the handoff numbers.
 */
object EwmSpaceTokens {
    /** Slider value gap / option chip horizontal pad band. */
    val controlGap: Dp = 10.dp

    /** Choice chip min width (DesignChoiceChips). */
    val choiceChipMinWidth: Dp = 56.dp

    /** Slider design track thickness. */
    val sliderTrackHeight: Dp = 2.dp

    /** Slider thumb diameter. */
    val sliderThumbSize: Dp = 20.dp
}

/** Canonical full-motion durations (ms) before [MotionPolicy] scaling. */
object EwmMotionTokens {
    /** BrandLogo / AboutPageLogo gradient sweep half-cycle. */
    const val logoSweepMs: Int = 2500

    /** Product shell About enter/exit (~config_mediumAnimTime). */
    const val shellMediumMs: Int = 340

    /** Product shell Launch↔Editor short crossfade/slide (ADR-0023 intentional route family). */
    const val shellShortMs: Int = 240

    /** Export progress wipe (Ing / Success). */
    const val exportWipeMs: Int = 400

    /** Save sheet format block animateContentSize / gallery FAB enter-exit. */
    const val contentSizeMs: Int = 220

    /** Editor option panel slide (legacy fragment_open_in medium ~300ms). */
    const val optionPanelSlideMs: Int = 300

    /** Editor option panel fade + gallery dialog slide/fade. */
    const val optionPanelFadeMs: Int = 200

    /** Gallery [AnimatedTransitionHost] enter delay before content appears. */
    const val dialogHostEnterDelayMs: Int = 50

    /** Gallery [AnimatedTransitionHost] exit wait before dispose. */
    const val dialogHostExitMs: Int = 300
}

/** State colors (error / success washes) — not a full semantic palette. */
object EwmStateTokens {
    val error: Color get() = md_theme_dark_error
    val errorContainer: Color get() = md_theme_dark_errorContainer
    /** Export overlay wash uses Material tertiary (see ExportProgressOverlay). */
    val exportWashTertiary: Color get() = md_theme_dark_tertiary
}
