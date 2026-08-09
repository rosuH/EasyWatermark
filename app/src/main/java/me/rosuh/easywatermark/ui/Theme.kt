package me.rosuh.easywatermark.ui


import android.os.Build
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import me.rosuh.easywatermark.ui.theme.AppTheme as SharedAppTheme
import me.rosuh.easywatermark.ui.theme.EwmMaterialShapes


/**
 * Android Material3 theme.
 *
 * Signature is unchanged: `AppTheme(darkTheme, dynamicColor, content)`.
 *
 * - **Dynamic-color branch** (`dynamicColor && Build.VERSION.SDK_INT >= S`): uses
 *   `dynamicDark/LightColorScheme(LocalContext.current)` **with [EwmMaterialShapes]** so product
 *   dialogs/sheets still inherit rectangle panel chrome under Material You colors.
 * - **Non-dynamic branch**: delegates to the commonMain [SharedAppTheme]
 *   (`me.rosuh.easywatermark.ui.theme.AppTheme`), which builds the static
 *   `Light/DarkColorScheme` + shapes from the relocated tokens.
 *
 * Parity (ADR-0011): production v2.10.0 is forced-dark (Theme.Material3.Dark, no DayNight).
 * The static color tokens + schemes now live in `:shared/commonMain` (`ui/theme/Color.kt` +
 * `ui/theme/Theme.kt`) so shared CMP screens can use the same product theme; the Android
 * dynamic-color path stays here because `dynamicDark/LightColorScheme` + `LocalContext` +
 * `Build.VERSION` are Android-only.
 */
@Composable
fun AppTheme(
    darkTheme: Boolean = true,
    dynamicColor: Boolean = false,
    content: @Composable() () -> Unit
) {
    if (dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        val context = LocalContext.current
        val colorScheme = if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        MaterialTheme(
            colorScheme = colorScheme,
            shapes = EwmMaterialShapes,
            content = content,
        )
    } else {
        SharedAppTheme(darkTheme = darkTheme, content = content)
    }
}
