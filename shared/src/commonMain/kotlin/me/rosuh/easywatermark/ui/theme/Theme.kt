package me.rosuh.easywatermark.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp

// the platform-neutral static Material3 theme. Relocated verbatim from the former
// `:app`-only `Theme.kt` (the static `LightColorScheme`/`DarkColorScheme` + the non-dynamic
// `AppTheme` branch). The Android dynamic-color path (`dynamicDark/LightColorScheme`) stays
// in the `:app`-only `AppTheme(darkTheme, dynamicColor, content)` wrapper, which delegates
// its non-dynamic branch here. No Android imports, no `Build.VERSION`, no `LocalContext` —
// pure common Material3, compiled for Android + JVM/desktop + iOS.

/** 0dp corners — visual rectangle; Material3 [Shapes] requires [CornerBasedShape]. */
private val ProductPanelCornerShape = RoundedCornerShape(0.dp)

/**
 * Product Material3 shape scale for olive editor chrome.
 *
 * **Large / extraLarge** are 0dp rounded corners (rectangle) so AlertDialog and ModalBottomSheet
 * inherit square panel chrome (no stock M3 rounded elevated card). **ExtraSmall / small /
 * medium** keep the 2dp chip radius used by product controls ([EwmTheme.shapes.chipRadiusDp]).
 */
val EwmMaterialShapes = Shapes(
    extraSmall = RoundedCornerShape(EwmTheme.shapes.chipRadiusDp),
    small = RoundedCornerShape(EwmTheme.shapes.chipRadiusDp),
    medium = RoundedCornerShape(EwmTheme.shapes.chipRadiusDp),
    large = ProductPanelCornerShape,
    extraLarge = ProductPanelCornerShape,
)

internal val LightColorScheme = lightColorScheme(
    primary = md_theme_light_primary,
    onPrimary = md_theme_light_onPrimary,
    primaryContainer = md_theme_light_primaryContainer,
    onPrimaryContainer = md_theme_light_onPrimaryContainer,
    secondary = md_theme_light_secondary,
    onSecondary = md_theme_light_onSecondary,
    secondaryContainer = md_theme_light_secondaryContainer,
    onSecondaryContainer = md_theme_light_onSecondaryContainer,
    tertiary = md_theme_light_tertiary,
    onTertiary = md_theme_light_onTertiary,
    tertiaryContainer = md_theme_light_tertiaryContainer,
    onTertiaryContainer = md_theme_light_onTertiaryContainer,
    error = md_theme_light_error,
    errorContainer = md_theme_light_errorContainer,
    onError = md_theme_light_onError,
    onErrorContainer = md_theme_light_onErrorContainer,
    background = md_theme_light_background,
    onBackground = md_theme_light_onBackground,
    outline = md_theme_light_outline,
    inverseOnSurface = md_theme_light_inverseOnSurface,
    inverseSurface = md_theme_light_inverseSurface,
    inversePrimary = md_theme_light_inversePrimary,
    surfaceTint = md_theme_light_surfaceTint,
    outlineVariant = md_theme_light_outlineVariant,
    scrim = md_theme_light_scrim,
    surface = md_theme_light_surface,
    onSurface = md_theme_light_onSurface,
    surfaceVariant = md_theme_light_surfaceVariant,
    onSurfaceVariant = md_theme_light_onSurfaceVariant,
)

internal val DarkColorScheme = darkColorScheme(
    primary = md_theme_dark_primary,
    onPrimary = md_theme_dark_onPrimary,
    primaryContainer = md_theme_dark_primaryContainer,
    onPrimaryContainer = md_theme_dark_onPrimaryContainer,
    secondary = md_theme_dark_secondary,
    onSecondary = md_theme_dark_onSecondary,
    secondaryContainer = md_theme_dark_secondaryContainer,
    onSecondaryContainer = md_theme_dark_onSecondaryContainer,
    tertiary = md_theme_dark_tertiary,
    onTertiary = md_theme_dark_onTertiary,
    tertiaryContainer = md_theme_dark_tertiaryContainer,
    onTertiaryContainer = md_theme_dark_onTertiaryContainer,
    error = md_theme_dark_error,
    errorContainer = md_theme_dark_errorContainer,
    onError = md_theme_dark_onError,
    onErrorContainer = md_theme_dark_onErrorContainer,
    background = md_theme_dark_background,
    onBackground = md_theme_dark_onBackground,
    outline = md_theme_dark_outline,
    inverseOnSurface = md_theme_dark_inverseOnSurface,
    inverseSurface = md_theme_dark_inverseSurface,
    inversePrimary = md_theme_dark_inversePrimary,
    surfaceTint = md_theme_dark_surfaceTint,
    outlineVariant = md_theme_dark_outlineVariant,
    scrim = md_theme_dark_scrim,
    surface = md_theme_dark_surface,
    onSurface = md_theme_dark_onSurface,
    surfaceVariant = md_theme_dark_surfaceVariant,
    onSurfaceVariant = md_theme_dark_onSurfaceVariant,
)

/**
 * Platform-neutral static Material3 theme. Uses the relocated [LightColorScheme]/
 * [DarkColorScheme] only — no dynamic-color logic (that stays in the `:app`-only overload
 * below, which delegates its non-dynamic branch here).
 *
 * Passes [EwmMaterialShapes] so product dialogs/sheets inherit rectangle panel chrome without
 * per-call shape overrides. Panel surface color stays [DesignEditorBg] via
 * [EwmTheme.colors.editorBackground] (dark `colorScheme.surface` already matches; sheets keep
 * the product token so dynamic-color Android does not paint Material You into export chrome).
 *
 * Parity (ADR-0011): production v2.10.0 is forced-dark, so [darkTheme] defaults to `true`.
 */
@Composable
fun AppTheme(
    darkTheme: Boolean = true,
    content: @Composable () -> Unit,
) {
    val colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme
    MaterialTheme(
        colorScheme = colorScheme,
        shapes = EwmMaterialShapes,
        content = content,
    )
}
