package me.rosuh.easywatermark.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import com.materialkolor.PaletteStyle
import com.materialkolor.dynamicColorScheme
import com.materialkolor.ktx.themeColor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * Content editor theme (ADR-0027): full dark M3 [ColorScheme] from the selected photo seed.
 *
 * Pure seed — no brand-amber harmonize. Wallpaper Material You stays on [DynamicColorCapability].
 */
object ContentEditorTheme {
    /** Debounce filmstrip selection before re-quantizing (ms). */
    const val SEED_DEBOUNCE_MS = 120L

    /**
     * Extract theme seed from [bitmap]. Returns null on failure so callers fall back to brand.
     * Safe to call off the main thread.
     */
    fun seedFromImage(
        bitmap: ImageBitmap,
        fallback: Color = DesignBrand,
    ): Color? = try {
        bitmap.themeColor(fallback = fallback)
    } catch (_: Throwable) {
        null
    }

    /**
     * Full dark scheme from [seed]. Non-composable so unit tests can assert roles without UI.
     * No brand harmonize.
     */
    fun darkSchemeFromSeed(seed: Color): ColorScheme =
        dynamicColorScheme(
            seedColor = seed,
            isDark = true,
            style = PaletteStyle.TonalSpot,
        )
}

/**
 * Applies content editor theme over [content] when [enabled] and a seed can be derived from
 * [seedBitmap]. Debounces selection changes; animates via short MaterialTheme swap (host may
 * also animate route). On failure / null bitmap / disabled → static brand [AppTheme].
 *
 * Call only around the **Editor** surface so Launch/About keep brand (+ Android wallpaper).
 */
@Composable
fun ContentEditorThemeHost(
    enabled: Boolean,
    seedBitmap: ImageBitmap?,
    /**
     * Identity for debounce (e.g. selected MediaRef value). Changing this restarts debounce
     * even if the bitmap instance is reused.
     */
    seedKey: Any? = seedBitmap,
    content: @Composable () -> Unit,
) {
    if (!enabled) {
        AppTheme(darkTheme = true, content = content)
        return
    }

    var scheme by remember { mutableStateOf<ColorScheme?>(null) }

    LaunchedEffect(enabled, seedKey, seedBitmap) {
        if (seedBitmap == null) {
            scheme = null
            return@LaunchedEffect
        }
        delay(ContentEditorTheme.SEED_DEBOUNCE_MS)
        val next = withContext(Dispatchers.Default) {
            try {
                val seed = ContentEditorTheme.seedFromImage(seedBitmap) ?: return@withContext null
                ContentEditorTheme.darkSchemeFromSeed(seed)
            } catch (_: Throwable) {
                null
            }
        }
        scheme = next
    }

    val active = scheme
    if (active != null) {
        MaterialTheme(
            colorScheme = active,
            shapes = EwmMaterialShapes,
            content = content,
        )
    } else {
        AppTheme(darkTheme = true, content = content)
    }
}
