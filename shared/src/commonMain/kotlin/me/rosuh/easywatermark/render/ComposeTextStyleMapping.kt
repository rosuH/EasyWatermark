package me.rosuh.easywatermark.render

import androidx.compose.ui.graphics.drawscope.DrawStyle
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import me.rosuh.easywatermark.data.model.TextPaintStyle
import me.rosuh.easywatermark.data.model.TextTypeface

/**
 * Maps [TextTypeface] / [TextPaintStyle] to Compose font and draw styles for common-raster text.
 *
 * Bold/italic are synthetic (regular Noto faces). Not byte-parity with native StaticLayout.
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
