package me.rosuh.easywatermark.render

import androidx.compose.ui.graphics.drawscope.DrawStyle
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import me.rosuh.easywatermark.data.model.TextPaintStyle
import me.rosuh.easywatermark.data.model.TextTypeface

/**
 * S4d-195: commonMain Compose-side mapping for the platform-neutral text-style model, shared by
 * Desktop/iOS Skiko renderers and Android common-raster text ([CommonWatermarkPipeline] via
 * `AndroidCommonRaster`, ADR-0018). Native `WatermarkRenderer` / `StaticLayout` remains measurement
 * and golden oracle only (not byte-identical).
 *
 * Android native-edge UI chrome may still map [TextPaintStyle] via `obtainSysStyle()` for non-raster
 * paint; production watermark text/icon cells use these Compose mappings on the common path.
 *
 * Bold/italic are **synthetic** (bundled Noto faces are regular-only; ADR-0010) and the `Stroke`
 * default width `0f` is a Skia hairline — perceptual Skiko honoring, not native-oracle byte parity.
 */
fun TextTypeface.toComposeFontStyle(): Pair<FontWeight, FontStyle> = when (this) {
    TextTypeface.Normal -> FontWeight.Normal to FontStyle.Normal
    TextTypeface.Italic -> FontWeight.Normal to FontStyle.Italic
    TextTypeface.Bold -> FontWeight.Bold to FontStyle.Normal
    TextTypeface.BoldItalic -> FontWeight.Bold to FontStyle.Italic
}

fun TextPaintStyle.toComposeDrawStyle(): DrawStyle = when (this) {
    TextPaintStyle.Fill -> Fill
    TextPaintStyle.Stroke -> Stroke()
}
