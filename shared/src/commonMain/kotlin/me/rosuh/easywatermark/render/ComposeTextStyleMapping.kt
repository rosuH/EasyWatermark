package me.rosuh.easywatermark.render

import androidx.compose.ui.graphics.drawscope.DrawStyle
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import me.rosuh.easywatermark.data.model.TextPaintStyle
import me.rosuh.easywatermark.data.model.TextTypeface

/**
 * S4d-195: commonMain Compose-side mapping for the platform-neutral text-style model, shared by the
 * Desktop ([DesktopWatermarkTextRenderer]) and iOS ([IosWatermarkRenderer]) Skiko renderers — the single
 * source of truth that replaces the formerly-duplicated private mappings in those two files.
 *
 * **Android does NOT use these.** Android watermark text stays native (`StaticLayout`; S4d-17 Option C):
 * it maps [TextPaintStyle] to `android.graphics.Paint.Style` via the Android edge `obtainSysStyle()` and
 * has no Compose `FontWeight`/`DrawStyle` mapping. These functions are the Desktop/iOS Compose path only.
 *
 * Bold/italic are **synthetic** (the bundled Noto faces are regular-only; ADR-0010) and the `Stroke`
 * default width `0f` is a Skia hairline — both are **perceptual** Skiko honoring, not Android byte-parity
 * (S4d-112 / S4d-113). Branches are copied verbatim from the prior per-renderer mappings, so render output
 * is unchanged.
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
