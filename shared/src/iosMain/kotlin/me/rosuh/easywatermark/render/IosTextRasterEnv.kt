package me.rosuh.easywatermark.render

import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.createFontFamilyResolver
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection

/**
 * iOS (Skiko) text-raster environment — analogue of Desktop's shared resolver + image-space density.
 *
 * Production Text mode uses [FontFamily.Default] (ADR-0025). Bundled Noto face loading was removed
 * with the production font payloads; there is no byte-`Font` / NSBundle loader on the product path.
 *
 * Density is `Density(1f)` so `1.sp == 1px` in image space (S3a), matching other platforms.
 */
/** J5: text raster env — not called from Swift. */
internal object IosTextRasterEnv {

    /**
     * Process-wide shared Skiko [FontFamily.Resolver] — avoid allocating a resolver per
     * preview/export raster call. No Android Context (safe for process singleton).
     */
    private val sharedFontFamilyResolver by lazy {
        createFontFamilyResolver()
    }

    /** The iOS (Skiko) text-raster environment: shared resolver + image-space density. */
    fun textRasterEnv(density: Density = Density(1f)): TextRasterEnv = TextRasterEnv(
        fontFamilyResolver = sharedFontFamilyResolver,
        density = density,
        layoutDirection = LayoutDirection.Ltr,
    )
}
