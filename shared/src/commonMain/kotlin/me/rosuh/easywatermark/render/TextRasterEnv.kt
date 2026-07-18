package me.rosuh.easywatermark.render

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection

/**
 * S4d-3: the **platform-bootstrap value object** for the commonMain text raster — the explicit,
 * injected boundary ADR-0004 calls out ("Headless TextMeasurer needs platform bootstrap"). It
 * carries exactly the three commonMain types a headless [androidx.compose.ui.text.TextMeasurer]
 * needs to both measure AND paint text offscreen: a [FontFamily.Resolver], a [Density], and a
 * [LayoutDirection].
 *
 * It is deliberately the same triple as the Android app's measurement seam `TextMeasureEnv`
 * (`TextMeasureEnv.kt`), so a future Android adapter can build ONE env and feed it to both the
 * measurement seam and this raster. It is a SEPARATE type (not an alias of `TextMeasureEnv`) so the
 * raster contract is explicit and the measurement seam's API is not silently widened by this slice.
 *
 * Platform coupling lives ONLY in how a caller obtains the [fontFamilyResolver]:
 *  - Android: `createFontFamilyResolver(context)` (needs a `Context`);
 *  - desktop (Skiko): `createFontFamilyResolver()` (no `Context`);
 *  - iOS (UIKit): its own Skiko/UIKit variant.
 * There is NO commonMain zero-arg factory for the resolver, so it is never constructed inside
 * `:shared` — callers build a [TextRasterEnv] at their platform boundary and pass it in.
 *
 * Production (ADR-0018 / Option C2): Android preview/export use this env via
 * [me.rosuh.easywatermark.render.AndroidCommonRaster] → [WatermarkCellComposer.composeTextCell]
 * (Desktop/iOS likewise). Native `WatermarkRenderer` remains a measurement/golden oracle only —
 * common and native text rasters are **not** claimed byte-identical (CJK/engine delta expected).
 */
data class TextRasterEnv(
    val fontFamilyResolver: FontFamily.Resolver,
    val density: Density,
    val layoutDirection: LayoutDirection = LayoutDirection.Ltr,
)

/**
 * S4d-3: the platform-neutral **text content** input for the commonMain watermark cell — the
 * commonMain analogue of the Android renderer's `TextPaint`-driven text+colour, without any
 * `android.graphics.*` or `android.text.*`. Carries the [text] to raster, the Compose [style] used
 * to both measure and paint it, and the fill [color] (the Android renderer passes `textPaint.color`
 * to `StaticLayout`; [color] is the equivalent for `MultiParagraph.paint`).
 *
 * `style.fontSize` is interpreted under the [TextRasterEnv.density] the raster is called with; the
 * production renderer uses `Density(1f)` so `1.sp == 1px` (image-space sizing, S3a).
 */
data class WatermarkTextContent(
    val text: String,
    val style: TextStyle,
    val color: Color = Color.White,
)
