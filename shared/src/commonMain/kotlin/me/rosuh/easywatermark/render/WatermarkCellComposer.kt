package me.rosuh.easywatermark.render

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.ImageBitmapConfig
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection

/**
 * S4d-2: the first real piece of the commonMain watermark renderer (CMP plan C2 / ADR-0004): an
 * **offscreen cell composition primitive** built on multiplatform Compose graphics, sized by the
 * shared [WatermarkGeometry] core. Compiles and runs on Android + desktop(JVM) + iOS.
 *
 * This composes ONE watermark "cell" into an offscreen [ImageBitmap], mirroring the Android
 * production renderer's cell pipeline 1:1 (`WatermarkRenderer.buildTextShader`/`buildIconShader`):
 *
 *  - cell size = rotated-AABB of the content box (`WatermarkGeometry.rotatedCellWidth/Height`)
 *    expanded by the gap percents (`horizontalGap`/`verticalGap`) - the SAME math the Android
 *    renderer uses;
 *  - draw onto an offscreen surface, rotate about the cell centre, draw the content centred.
 *
 * SCOPE (deliberately narrow, S4d-2):
 *  - This is the **offscreen -> draw -> rotate -> ImageBitmap** scaffold only. The content here is a
 *    placeholder rect at the content bounds - it is NOT the text/icon raster. The text raster needs
 *    a per-platform `FontFamily.Resolver`/`TextMeasurer` bootstrap (ADR-0004 "headless TextMeasurer
 *    needs platform bootstrap"); the icon raster needs platform image decode - both are the NEXT
 *    slice (S4d-3+).
 *  - **Not wired into production.** Android preview (`EditorScreen.WaterMarkCanvas`) and export
 *    (`MainViewModel.generateImage`) still use the Android-only `WatermarkRenderer` seam, so the
 *    strict renderer goldens and on-device behaviour are unchanged by this slice. This primitive is
 *    verified independently by `WatermarkCellComposerTest` (commonTest / `:shared:desktopTest`).
 *  - No tiling/REPEAT/CLAMP here - composition over the photo stays in `WatermarkRenderer.compose`.
 */
object WatermarkCellComposer {

    /**
     * Compose one rotated, gap-spaced watermark cell into an offscreen [ImageBitmap].
     *
     * @param contentWidth  width of the content box (e.g. measured text / scaled icon), px
     * @param contentHeight height of the content box, px
     * @param degree        rotation in degrees (matches `WaterMark.degree`)
     * @param hGapPercent   horizontal gap percent (0 -> adjacent, 100 -> 2x); matches `WaterMark.hGap`
     * @param vGapPercent   vertical gap percent; matches `WaterMark.vGap`
     * @param contentColor  fill of the content rect (placeholder until the text/icon raster lands)
     * @param backgroundColor cell background (transparent by default, like the production cell)
     */
    fun composeRotatedCell(
        contentWidth: Int,
        contentHeight: Int,
        degree: Float,
        hGapPercent: Int = 0,
        vGapPercent: Int = 0,
        contentColor: Color = Color.White,
        backgroundColor: Color = Color.Transparent,
    ): ImageBitmap {
        val cw = contentWidth.coerceAtLeast(1).toFloat()
        val ch = contentHeight.coerceAtLeast(1).toFloat()

        // SAME sizing math as WatermarkRenderer (Android): rotated-AABB then gap expansion.
        val fixWidth = WatermarkGeometry.rotatedCellWidth(cw, ch, degree)
        val fixHeight = WatermarkGeometry.rotatedCellHeight(cw, ch, degree)
        val finalWidth = WatermarkGeometry.horizontalGap(fixWidth.toInt(), hGapPercent).coerceAtLeast(1)
        val finalHeight = WatermarkGeometry.verticalGap(fixHeight.toInt(), vGapPercent).coerceAtLeast(1)

        val bitmap = ImageBitmap(finalWidth, finalHeight, ImageBitmapConfig.Argb8888)
        CanvasDrawScope().draw(
            density = Density(1f),
            layoutDirection = LayoutDirection.Ltr,
            canvas = Canvas(bitmap),
            size = Size(finalWidth.toFloat(), finalHeight.toFloat()),
        ) {
            if (backgroundColor != Color.Transparent) {
                drawRect(color = backgroundColor)
            }
            // Rotate about the cell centre; mirrors `canvas.rotate(degree, finalW/2, finalH/2)`.
            rotate(degrees = degree, pivot = Offset(finalWidth / 2f, finalHeight / 2f)) {
                // Content box centred in the cell (placeholder; text/icon raster is the next slice).
                drawRect(
                    color = contentColor,
                    topLeft = Offset((finalWidth - cw) / 2f, (finalHeight - ch) / 2f),
                    size = Size(cw, ch),
                )
            }
        }
        return bitmap
    }
}
