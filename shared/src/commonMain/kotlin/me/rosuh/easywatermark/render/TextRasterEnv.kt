package me.rosuh.easywatermark.render

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection

/**
 * Platform-injected bootstrap for commonMain text measure/paint.
 *
 * Holds [FontFamily.Resolver], [Density], and [LayoutDirection] for a headless [TextMeasurer].
 * Built at the platform boundary (Android needs a `Context` for the resolver).
 */
data class TextRasterEnv(
    val fontFamilyResolver: FontFamily.Resolver,
    val density: Density,
    val layoutDirection: LayoutDirection = LayoutDirection.Ltr,
)

/**
 * Text content for [WatermarkCellComposer.composeTextCell]: string, Compose [TextStyle], and fill color.
 *
 * [style.fontSize] uses [TextRasterEnv.density] (production `Density(1f)` ⇒ image-space 1.sp == 1px).
 */
data class WatermarkTextContent(
    val text: String,
    val style: TextStyle,
    val color: Color = Color.White,
)
