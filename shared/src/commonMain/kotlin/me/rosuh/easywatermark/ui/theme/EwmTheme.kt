package me.rosuh.easywatermark.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
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
    /** Olive editor dialog / ModalBottomSheet panel (Figma contract). */
    val panel: EwmPanelTokens = EwmPanelTokens
}

/**
 * Product panel chrome for confirms + bottom sheets.
 * Shape inherits [MaterialTheme.shapes] after [AppTheme].
 * Container fill tracks [editorChromeColor] so content editor theme (ADR-0027) paints sheets
 * with the photo surface; brand olive remains the fallback outside a scheme.
 */
object EwmPanelTokens {
    /** Sheet / dialog shape — [MaterialTheme.shapes.large] under [AppTheme] (= [RectangleShape]). */
    val shape: Shape
        @Composable
        @ReadOnlyComposable
        get() = MaterialTheme.shapes.large

    /** Confirm dialog shape — [MaterialTheme.shapes.extraLarge] under [AppTheme] (= [RectangleShape]). */
    val dialogShape: Shape
        @Composable
        @ReadOnlyComposable
        get() = MaterialTheme.shapes.extraLarge

    /** Fallback when outside [MaterialTheme] (tests / previews without AppTheme). */
    val rectangle: Shape get() = RectangleShape

    val containerColor: Color
        @Composable
        @ReadOnlyComposable
        get() = editorChromeColor()

    /** Non-composable brand fallback for hosts/tests without ambient theme. */
    val brandContainerColor: Color get() = DesignEditorBg

    val tonalElevation: Dp = 0.dp
}

/** Brand / surface colors from Figma handoff (preview_edit). */
object EwmColorTokens {
    val brand: Color get() = DesignBrand
    /** Static brand olive. Prefer [editorChromeColor] in Composables under content theme. */
    val editorBackground: Color get() = DesignEditorBg
    /**
     * Live editor surface fill — content scheme background when themed, else brand olive.
     * ADR-0027 option B single chrome source for Compose consumers.
     */
    val editorChrome: Color
        @Composable
        @ReadOnlyComposable
        get() = editorChromeColor()
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

    /** Export success check appear (prod [View.appear] ~200ms). */
    const val exportCheckAppearMs: Int = 200

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

    /** Gallery cell select scale (prod GalleryAdapter ObjectAnimator 200ms). */
    const val gallerySelectMs: Int = 200

    /** Gallery selected thumbnail scale factor (prod 0.8). */
    const val gallerySelectScale: Float = 0.8f

    /**
     * Historical multi-image preview switch crossfade floor (aspect-similar).
     * Product 2026-08-12: [previewCrossfadeDurationMs] always returns 0 (hard-cut switch).
     * Tokens kept for possible re-enable / tests that document the design range.
     */
    const val previewCrossfadeMinMs: Int = 180

    /** Historical multi-image preview switch crossfade ceiling (large aspect deltas). */
    const val previewCrossfadeMaxMs: Int = 320

    /** First watermark preview alpha reveal (prod WaterMarkImageView drawableAlpha 450ms). */
    const val firstPreviewRevealMs: Int = 450

    /** Soft caret blink half-cycle in text content summary (decorative loop). */
    const val textCaretBlinkMs: Int = 900
}

/** State colors (error / success washes) — not a full semantic palette. */
object EwmStateTokens {
    val error: Color get() = md_theme_dark_error
    val errorContainer: Color get() = md_theme_dark_errorContainer
    /** Export overlay wash uses Material tertiary (see ExportProgressOverlay). */
    val exportWashTertiary: Color get() = md_theme_dark_tertiary
}
