package me.rosuh.easywatermark.ui.theme

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import com.materialkolor.PaletteStyle
import com.materialkolor.dynamicColorScheme
import com.materialkolor.ktx.animateColorScheme
import com.materialkolor.ktx.themeColor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
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
     * Max long-edge for MCU / MaterialKolor quantize input.
     * Hosts must downscale before [seedFromImage] — never feed full preview / full-res.
     */
    const val SEED_MAX_EDGE = 128

    /**
     * Extract theme seed from [bitmap]. Returns null on failure so callers fall back to brand.
     * Safe to call off the main thread. Callers should pass ≤ [SEED_MAX_EDGE] bitmaps.
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

    /**
     * Target scheme for the Editor host. Photo scheme when ready; otherwise the same static
     * dark brand scheme [AppTheme] uses. Keeping one target type lets the host wrap content in a
     * **single** [MaterialTheme] instead of if/else remounting the Editor tree (first-image jump).
     */
    fun targetScheme(photoScheme: ColorScheme?): ColorScheme = photoScheme ?: DarkColorScheme
}

/**
 * Monotonic job token so filmstrip churn discards stale quantize results even when
 * CPU-bound [ContentEditorTheme.seedFromImage] does not cooperate with cancellation mid-flight.
 */
internal class ContentThemeJobSequencer {
    private var latest: Int = 0

    fun begin(): Int {
        latest += 1
        return latest
    }

    fun isCurrent(token: Int): Boolean = token == latest
}

/**
 * Applies content editor theme over [content] when [enabled] and a seed can be derived from
 * [seedBitmap]. Debounces selection changes. On failure / null bitmap / disabled → static brand
 * scheme (same tokens as [AppTheme]).
 *
 * Stale jobs: each launch takes a [ContentThemeJobSequencer] token; results apply only if still
 * current after debounce + Default work (and [ensureActive] after suspension). Rapid seedKey
 * changes cancel the prior [LaunchedEffect] and also fail the token check.
 *
 * **No if/else theme remount:** brand and photo share one [MaterialTheme] call site so the first
 * photo seed does not dispose/recreate the Editor subtree (that remount looked like a full UI
 * refresh). ADR-0027 short transition via MaterialKolor [animateColorScheme].
 *
 * [onChromeColorChange] reports the Editor chrome fill for hosts that paint **outside** this
 * subtree (Desktop root Box / AWT title band). Tracks the **animated** background so external
 * chrome does not hard-cut ahead of the Compose surface.
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
    /**
     * Desktop (and similar) window chrome outside this host. Always invoked with the color that
     * root/AWT should use — brand olive when inactive / loading, scheme background when ready.
     */
    onChromeColorChange: ((Color) -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    if (!enabled) {
        LaunchedEffect(Unit) {
            onChromeColorChange?.invoke(EditorChromeColor.brand)
        }
        AppTheme(darkTheme = true, content = content)
        return
    }

    var scheme by remember { mutableStateOf<ColorScheme?>(null) }
    val jobSeq = remember { ContentThemeJobSequencer() }

    LaunchedEffect(enabled, seedKey, seedBitmap) {
        val token = jobSeq.begin()
        if (seedBitmap == null) {
            if (jobSeq.isCurrent(token)) {
                scheme = null
            }
            return@LaunchedEffect
        }
        delay(ContentEditorTheme.SEED_DEBOUNCE_MS)
        ensureActive()
        if (!jobSeq.isCurrent(token)) return@LaunchedEffect
        val bitmap = seedBitmap
        val next = withContext(Dispatchers.Default) {
            try {
                val seed = ContentEditorTheme.seedFromImage(bitmap) ?: return@withContext null
                ContentEditorTheme.darkSchemeFromSeed(seed)
            } catch (_: Throwable) {
                null
            }
        }
        ensureActive()
        if (!jobSeq.isCurrent(token)) return@LaunchedEffect
        scheme = next
    }

    val target = ContentEditorTheme.targetScheme(scheme)
    val transitionMs = motionDurationMs(
        currentMotionPolicy(),
        EwmTheme.motion.contentThemeTransitionMs,
    )
    val animated = animateColorScheme(
        colorScheme = target,
        animationSpec = {
            tween(durationMillis = transitionMs, easing = FastOutSlowInEasing)
        },
    )
    SideEffect {
        onChromeColorChange?.invoke(animated.background)
    }
    MaterialTheme(
        colorScheme = animated,
        shapes = EwmMaterialShapes,
        content = content,
    )
}
