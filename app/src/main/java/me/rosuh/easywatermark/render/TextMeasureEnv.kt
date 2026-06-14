package me.rosuh.easywatermark.render

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection

/**
 * C2b text-measurement seam — **platform-neutral half** (ACSP 20260614-002242, boundary-polished
 * 20260614-080727; adopted as **product measurement** in S3b/D1). Used by the renderer text path
 * ([WatermarkRenderer.buildTextShader]) for the watermark text-cell box; drawing stays legacy
 * `StaticLayout`. The instrumented gate `WatermarkCellParityGateTest` pins it against the signed CJK
 * baseline (non-CJK exact; CJK width exact + signed height delta).
 *
 * This file contains **only platform-neutral, commonMain-ready** declarations — no Android `Context` or
 * `TextPaint`, no `createFontFamilyResolver`/`sp`. It constructs `TextMeasurer` directly (not
 * `@Composable`), so it lifts to `:shared/commonMain` unchanged once `:shared` gains Compose (the
 * `org.jetbrains.compose` ui-text dependency decision flagged in `decisions.md`/ADR-0004 — that move is
 * a later C4-era slice; S3b keeps it app-side, dependency-free).
 *
 * The Android-specific bootstrap (`androidTextMeasureEnv(context)`) and the `TextPaint → TextStyle`
 * adapter (`toWatermarkTextStyle()`) live in the sibling `AndroidTextMeasureEnv.kt`.
 */

/**
 * The minimal text-measurement environment the renderer needs, as a value object (no `Context`):
 * a [FontFamily.Resolver], a [Density], and a [LayoutDirection]. Platform-neutral / commonMain-ready.
 *
 * S3b (D1): **product measurement** — supplied to [WatermarkRenderer.buildTextShader] at the
 * preview/export call sites (built via `androidTextMeasureEnv(context)`). `public` because it appears
 * in the renderer's measurement API signature.
 */
data class TextMeasureEnv(
    val fontFamilyResolver: FontFamily.Resolver,
    val density: Density,
    val layoutDirection: LayoutDirection = LayoutDirection.Ltr,
)

/**
 * The headless text-cell measurement seam. Platform-neutral: given an injected [TextMeasureEnv] and a
 * [TextStyle], returns the laid-out text size in px — the `StaticLayout.width/height` analogue.
 * S3b: the renderer's product text-cell measurement.
 */
internal object WatermarkTextMeasurer {
    fun measure(env: TextMeasureEnv, text: String, style: TextStyle): IntSize =
        TextMeasurer(env.fontFamilyResolver, env.density, env.layoutDirection)
            .measure(AnnotatedString(text), style = style).size
}
