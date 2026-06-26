package me.rosuh.easywatermark.render

import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.font.createFontFamilyResolver
import androidx.compose.ui.text.platform.Font
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection

/**
 * S4d-20B: the **iOS text-raster environment + bundled-font boundary** — the iOS analogue of the Desktop
 * [DesktopWatermarkTextRenderer]'s env/font pieces (S4d-18). iOS, like desktop, uses a **Skiko** backend,
 * so the platform bootstrap is the same shape:
 *  - the font resolver is the no-`Context` `createFontFamilyResolver()` (skiko), wired into the neutral
 *    [TextRasterEnv];
 *  - a bundled [FontFamily] is built from font **bytes** via the skiko byte-`Font` factory
 *    (`androidx.compose.ui.text.platform.Font`) — **no compose-resources / CMP-9547**.
 *
 * Density is `Density(1f)` to match the image-space convention (`1.sp == 1px`, S3a) used on every platform.
 *
 * ### Font-byte acquisition is the caller's job (documented boundary, not a gap)
 * Unlike desktop (JVM classpath `getResourceAsStream`), iOS/Kotlin-Native has **no classpath resource
 * loader**, and `compose-resources` (the usual CMP bundling path) is **forbidden** here. So this boundary
 * deliberately takes the already-loaded font **`ByteArray`s as parameters** — it does NOT decide where they
 * come from. A production iOS app supplies them from the app bundle
 * (`NSBundle.mainBundle.pathForResource(...)` → `NSData` → `ByteArray`) when the iOS app target lands (C5);
 * a test supplies its own bytes. This keeps the slice honest: the *rendering* boundary is proven now;
 * *packaging* the 8 MB CJK font into an iOS bundle is a C5 concern (see `ios-decode-font-boundary.md`).
 */
object IosTextRasterEnv {

    /** The iOS (Skiko) text-raster environment: skiko resolver + image-space density. */
    fun textRasterEnv(density: Density = Density(1f)): TextRasterEnv = TextRasterEnv(
        fontFamilyResolver = createFontFamilyResolver(),
        density = density,
        layoutDirection = LayoutDirection.Ltr,
    )

    /**
     * Build the bundled Latin + CJK watermark [FontFamily] from the supplied font bytes (e.g. Noto Sans +
     * Noto Sans SC), via the skiko byte-`Font` factory. [latinFirst] lists the Latin face first (the
     * owner's Latin+CJK order, S4d-16); `false` keeps CJK-first. Bold/Italic are synthesized (no bundled
     * bold/italic faces, per ADR-0010). Byte acquisition is the caller's responsibility (see class KDoc).
     */
    fun bundledFontFamily(
        latinBytes: ByteArray,
        cjkBytes: ByteArray,
        latinFirst: Boolean = true,
    ): FontFamily {
        val latin = Font("NotoSansLatin", latinBytes, FontWeight.Normal, FontStyle.Normal)
        val cjk = Font("NotoSansSC", cjkBytes, FontWeight.Normal, FontStyle.Normal)
        return if (latinFirst) FontFamily(latin, cjk) else FontFamily(cjk, latin)
    }
}
