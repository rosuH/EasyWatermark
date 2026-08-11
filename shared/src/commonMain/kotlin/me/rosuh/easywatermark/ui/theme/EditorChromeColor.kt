package me.rosuh.easywatermark.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.graphics.Color

/**
 * Single source for Editor chrome fill (ADR-0027 option B).
 *
 * - Content editor theme active → photo-seeded [ColorScheme.background]
 * - Else → brand olive [DesignEditorBg]
 *
 * Use for Desktop root/AWT, [ProductShellHost] letterbox, top-bar fallbacks, panel fills
 * that must track the Editor surface. Do **not** use to drive Launch/About product chrome
 * away from brand when those screens sit under static [AppTheme] (brand background == olive).
 */
object EditorChromeColor {
    /** Brand product olive — Launch / About / content-theme off. */
    val brand: Color get() = DesignEditorBg

    /**
     * Resolve chrome fill from an optional content [ColorScheme].
     * Pure — unit-testable without Compose.
     */
    fun resolve(contentScheme: ColorScheme?): Color =
        contentScheme?.background?.takeUnless { it == Color.Unspecified } ?: brand

    /**
     * Selected-control accent (chips, tabs). Content theme → scheme primary; else brand amber.
     */
    fun resolveAccent(contentScheme: ColorScheme?): Color =
        contentScheme?.primary?.takeUnless { it == Color.Unspecified } ?: DesignBrand

    /**
     * Selected chip/container fill under content theme; brand chip token otherwise.
     */
    fun resolveSelectedContainer(contentScheme: ColorScheme?): Color =
        contentScheme?.primaryContainer?.takeUnless { it == Color.Unspecified }
            ?: DesignChipSelected
}

/**
 * Live chrome fill from the ambient [MaterialTheme.colorScheme].
 * Under [ContentEditorThemeHost] this is the photo background; under brand [AppTheme] it is olive.
 */
@Composable
@ReadOnlyComposable
fun editorChromeColor(): Color {
    val bg = MaterialTheme.colorScheme.background
    return if (bg != Color.Unspecified) bg else EditorChromeColor.brand
}

/** Live accent (chips / selected labels) from ambient scheme, brand fallback. */
@Composable
@ReadOnlyComposable
fun editorAccentColor(): Color {
    val primary = MaterialTheme.colorScheme.primary
    return if (primary != Color.Unspecified) primary else DesignBrand
}

/** Live selected-container fill from ambient scheme, brand chip fallback. */
@Composable
@ReadOnlyComposable
fun editorSelectedContainerColor(): Color {
    val c = MaterialTheme.colorScheme.primaryContainer
    return if (c != Color.Unspecified) c else DesignChipSelected
}
