package me.rosuh.easywatermark.render

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.ImageBitmapConfig
import androidx.compose.ui.text.TextStyle

/**
 * C4.3 COMPILE WITNESS ONLY — proves `:shared/commonMain` can resolve and compile the Compose
 * Graphics/text/runtime types the future commonMain watermark renderer will need (ADR-0004 / CMP * plan C2), on Android + desktop(JVM) + iOS, now that the Compose lineage is unified.
 *
 * Intentionally NOT a renderer: no watermark logic, no drawing/tiling, no [WatermarkGeometry] use,
 * no production call site — it only references the types so every target must resolve the
 * multiplatform Compose artifacts. Replace/remove it when the real renderer slice (+) begins;
 * removing it is a no-op for the rest of the codebase.
 */
internal object ComposeTypeProbe {

    /** Touches `androidx.compose.ui.graphics` (ImageBitmap factory is a common expect/actual). */
    fun makeCell(width: Int, height: Int): ImageBitmap =
        ImageBitmap(width.coerceAtLeast(1), height.coerceAtLeast(1), ImageBitmapConfig.Argb8888)

    /** Touches `androidx.compose.ui.text` (TextStyle / TextUnit). */
    fun styleFontSize(style: TextStyle): Float = style.fontSize.value
}

/** Touches the Compose compiler plugin (a `@Composable` must be transformed) + `androidx.compose.runtime`. */
@Composable
internal fun composeCompilerProbe(): Int = 0
