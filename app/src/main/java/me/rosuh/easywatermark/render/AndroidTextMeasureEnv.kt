package me.rosuh.easywatermark.render

import android.content.Context
import android.text.TextPaint
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.font.createFontFamilyResolver
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.sp

/**
 * C2b text-measurement seam — **Android glue half** (ACSP 20260614-002242, boundary-polished
 * 20260614-080727; adopted as **product measurement** in S3b/D1). Builds the platform-neutral
 * [TextMeasureEnv] / drives [WatermarkTextMeasurer] (both in `TextMeasureEnv.kt`) from Android
 * `Context` / `TextPaint`. Now called by the product text path
 * ([me.rosuh.easywatermark.render.WatermarkRenderer.buildTextShader]) via the preview/export call
 * sites (`EditorScreen.WaterMarkCanvas`, `MainViewModel.generateImage`); drawing stays legacy `StaticLayout`.
 *
 * These two declarations are the parts that MUST stay Android-side (they touch `Context` and the legacy
 * `TextPaint`); the neutral seam in `TextMeasureEnv.kt` stays free of them so it can move to
 * `:shared/commonMain` unchanged. Behavior is identical to the pre-split file (no logic change).
 */

/**
 * Android bootstrap: build a [TextMeasureEnv] at the preview/export boundary where a [Context] already
 * exists. `Density(1f)` ⇒ image-space (`1.sp == 1px`), the C2b "image-space sizing" direction,
 * decoupled from any view/window density.
 */
internal fun androidTextMeasureEnv(context: Context): TextMeasureEnv =
    TextMeasureEnv(
        fontFamilyResolver = createFontFamilyResolver(context),
        density = Density(1f),
        layoutDirection = LayoutDirection.Ltr,
    )

/**
 * Android adapter: translate the legacy measurement-relevant fields of a [TextPaint] (system default
 * family + style flags + px size) into a Compose [TextStyle], with `includeFontPadding = true` to match
 * `StaticLayout`'s default. Mirrors session 215406's mapping exactly (behavior-preserving for the gate).
 */
internal fun TextPaint.toWatermarkTextStyle(): TextStyle {
    val tf = typeface
    return TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = if (tf?.isBold == true) FontWeight.Bold else FontWeight.Normal,
        fontStyle = if (tf?.isItalic == true) FontStyle.Italic else FontStyle.Normal,
        fontSize = textSize.sp, // px == sp under Density(1f)
        platformStyle = PlatformTextStyle(includeFontPadding = true),
    )
}
