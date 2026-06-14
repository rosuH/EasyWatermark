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
 * 20260614-080727). NOT product code: `internal` and **not called by any product path**
 * (`WaterMarkImageView`/`MainViewModel`/`EditorScreen` untouched). Today only the instrumented gate
 * `WatermarkCellParityGateTest` references it (via the Android glue in `AndroidTextMeasureEnv.kt`).
 *
 * This file contains **only platform-neutral, commonMain-ready** declarations — no Android `Context` or
 * `TextPaint`, no `createFontFamilyResolver`/`sp`. It constructs `TextMeasurer` directly (not
 * `@Composable`), so it lifts to `:shared/commonMain` unchanged once `:shared` gains Compose (the
 * `org.jetbrains.compose` ui-text dependency decision flagged in `decisions.md`/ADR-0004 — deliberately
 * out of this slice; the candidate stays app-side and dependency-free).
 *
 * The Android-specific bootstrap (`androidTextMeasureEnv(context)`) and the `TextPaint → TextStyle`
 * adapter (`toWatermarkTextStyle()`) live in the sibling `AndroidTextMeasureEnv.kt`.
 */

/**
 * The minimal text-measurement environment the future renderer needs, as a value object (no `Context`):
 * a [FontFamily.Resolver], a [Density], and a [LayoutDirection]. Platform-neutral / commonMain-ready.
 */
internal data class TextMeasureEnv(
    val fontFamilyResolver: FontFamily.Resolver,
    val density: Density,
    val layoutDirection: LayoutDirection = LayoutDirection.Ltr,
)

/**
 * The headless text-cell measurement seam. Platform-neutral: given an injected [TextMeasureEnv] and a
 * [TextStyle], returns the laid-out text size in px — the `StaticLayout.width/height` analogue.
 */
internal object WatermarkTextMeasurer {
    fun measure(env: TextMeasureEnv, text: String, style: TextStyle): IntSize =
        TextMeasurer(env.fontFamilyResolver, env.density, env.layoutDirection)
            .measure(AnnotatedString(text), style = style).size
}
