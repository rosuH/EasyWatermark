package me.rosuh.easywatermark.render

import androidx.compose.ui.text.font.createFontFamilyResolver
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection

/**
 * S4d-3 TEST-SCOPE bootstrap for [TextRasterEnv] on the desktop(JVM/Skiko) target.
 *
 * The platform font resolver is the one irreducibly platform-specific piece of the commonMain text
 * raster (ADR-0004). On desktop the Skiko backend ships a **Context-free** factory,
 * `createFontFamilyResolver()` from `androidx.compose.ui.text.font` (`ui-text-desktop`'s
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
