package me.rosuh.easywatermark.render

import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.font.createFontFamilyResolver
import androidx.compose.ui.text.platform.Font
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection

/**
 * TEST-SCOPE bootstrap for [TextRasterEnv] on the desktop(JVM/Skiko) target.
 *
 * The platform font resolver is the one irreducibly platform-specific piece of the commonMain text
 * Raster (ADR-0004). On desktop the Skiko backend ships a **Context-free** factory, * `createFontFamilyResolver()` from `androidx.compose.ui.text.font` (`ui-text-desktop`'s
 * `FontFamilyResolver.skiko.kt`). This helper wires that desktop resolver into the neutral
 * [TextRasterEnv] so `:shared:desktopTest` can actually execute the commonMain text raster
 * (`WatermarkCellComposer.composeTextCell`) on the JVM host.
 *
 * `Density(1f)` matches the production image-space convention (`1.sp == 1px`, S3a / the Android
 * `androidTextMeasureEnv`). This helper lives in the **`desktopTest`** source set only — it is not
 * in commonMain (no commonMain zero-arg resolver factory exists), not in `androidMain`, and cannot
 * reach `:app` or any production path.
 */
fun desktopTextRasterEnv(
    density: Density = Density(1f),
    layoutDirection: LayoutDirection = LayoutDirection.Ltr,
): TextRasterEnv = TextRasterEnv(
    fontFamilyResolver = createFontFamilyResolver(),
    density = density,
    layoutDirection = layoutDirection,
)

/**
 * (owner-approved C2, test-only): the **bundled Latin + CJK SC** watermark FontFamily for
 * `:shared:desktopTest`, loaded from the test-only resources under `desktopTest/resources/fonts/`:
 * - `NotoSans-Regular.ttf` (Noto Sans Latin static Regular, OFL-1.1)
 * - `NotoSansSC-Regular.otf` (Noto Sans CJK SC static Regular, full SC coverage, OFL-1.1)
 *
 * It is built via the Skiko **byte-`Font`** factory (`androidx.compose.ui.text.platform.Font`) — NO
 * Compose-resources / CMP-9547. Callers put this on `WatermarkTextContent.style.fontFamily`; the * `createFontFamilyResolver()` resolver (in [desktopTextRasterEnv]) resolves it. This is the desktop
 * half of the per-platform `TextRasterEnv` font injection ( F1): it proves the commonMain text
 * raster (`composeTextCell`) renders CJK with the bundled font on the JVM host. Bold/Italic are
 * synthesized (no bundled bold/italic faces). NOT a production path.
 *
 * round 2 (P1): the owner approved a **Latin + CJK** bundle, so [latinFirst]=true (default) lists
 * the Latin face first and the CJK SC face second — the Latin+CJK fallback order. [latinFirst]=false keeps
 * the CJK-first ordering for the round-1 comparison. NOTE: a Compose `FontFamily(fontA, fontB)` of the
 * same weight/style does not guarantee per-glyph fallback between the two user fonts; the parity test
 * logs BOTH orders so the actual behaviour (and any fallback limitation) is visible, not assumed.
 */
fun bundledLatinCjkFontFamily(latinFirst: Boolean = true): FontFamily {
    fun bytes(path: String): ByteArray =
        (object {}).javaClass.classLoader!!.getResourceAsStream(path)
            ?.use { it.readBytes() }
            ?: error("S4d-16 bundled test font not found on classpath: $path")
    val latin = Font("NotoSansLatin", bytes("fonts/NotoSans-Regular.ttf"), FontWeight.Normal, FontStyle.Normal)
    val cjk = Font("NotoSansSC", bytes("fonts/NotoSansSC-Regular.otf"), FontWeight.Normal, FontStyle.Normal)
    return if (latinFirst) FontFamily(latin, cjk) else FontFamily(cjk, latin)
}
