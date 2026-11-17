package me.rosuh.easywatermark.render

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.ImageBitmapConfig
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.sp
import me.rosuh.easywatermark.data.model.WatermarkTileMode
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The **executable text-raster proof** for the commonMain watermark cell. Runs on * `:shared:desktopTest` and exercises [WatermarkCellComposer.composeTextCell] end-to-end on the
 * JVM/Skiko host: a platform-resolved [TextRasterEnv] (via [desktopTextRasterEnv]) measures +
 * paints a real text cell offscreen, and we assert the raster actually contains visible text
 * pixels (the non-blank gate, mirroring 's `renders_nonblank_pixels`).
 *
 * This proves the commonMain text path works — `TextMeasurer` measure + `MultiParagraph.paint`
 * render through a platform-injected resolver. It does NOT assert cross-platform pixel parity with
 * the native Android `StaticLayout` oracle; that is gated separately. Production Android/Desktop/iOS
 * use this common text path (ADR-0018); native `WatermarkRenderer` remains comparison/golden oracle only.
 */
class WatermarkTextCellRasterTest {

    private val env = desktopTextRasterEnv()

    @Test
    fun text_cell_renders_visible_pixels() {
        val content = WatermarkTextContent(
            text = "GOLDEN",
            style = TextStyle(fontSize = 24.sp),
            color = Color.White,
        )
        val cell = WatermarkCellComposer.composeTextCell(env, content, degree = 0f)

        // White text on a transparent cell -> the raster must produce opaque/non-transparent pixels.
        val pixels = cell.toPixelMap()
        var nonBlank = 0
        var nonTransparent = 0
        for (y in 0 until pixels.height) {
            for (x in 0 until pixels.width) {
                val c = pixels[x, y]
                if (c != Color.Transparent) nonBlank++
                if (c.alpha > 0) nonTransparent++
            }
        }
        assertTrue(cell.width > 0 && cell.height > 0, "text cell must have positive dims")
        assertTrue(nonBlank > 0, "text cell must render visible (non-transparent) pixels")
        assertTrue(nonTransparent > 0, "text cell must render non-transparent (alpha>0) pixels")
    }

    @Test
    fun text_cell_dims_match_geometry_sizing() {
        val content = WatermarkTextContent(
            text = "GOLDEN",
            style = TextStyle(fontSize = 24.sp),
            color = Color.White,
        )
        val cell = WatermarkCellComposer.composeTextCell(
            env, content, degree = 0f, hGapPercent = 0, vGapPercent = 0,
        )
        // The cell box = rotated-AABB of the measured text + 0% gap; re-derive it from the SAME
        // measurement path + WatermarkGeometry to anchor the sizing (not the exact pixel size,
        // which depends on the host font — kept out of the assertion).
        val measured = androidx.compose.ui.text.TextMeasurer(
            env.fontFamilyResolver,
            env.density,
            env.layoutDirection,
        ).measure(androidx.compose.ui.text.AnnotatedString(content.text), style = content.style)
        val expectedW = WatermarkGeometry.rotatedCellWidth(
            measured.size.width.toFloat(), measured.size.height.toFloat(), 0f,
        ).toInt()
        val expectedH = WatermarkGeometry.rotatedCellHeight(
            measured.size.width.toFloat(), measured.size.height.toFloat(), 0f,
        ).toInt()
        assertEquals(expectedW, cell.width)
        assertEquals(expectedH, cell.height)
    }

    @Test
    fun gap_100_doubles_each_axis_for_text() {
        val content = WatermarkTextContent(
            text = "GOLDEN",
            style = TextStyle(fontSize = 24.sp),
            color = Color.White,
        )
        val base = WatermarkCellComposer.composeTextCell(env, content, degree = 0f)
        val gapped = WatermarkCellComposer.composeTextCell(
            env, content, degree = 0f, hGapPercent = 100, vGapPercent = 100,
        )
        assertEquals(base.width * 2, gapped.width)
        assertEquals(base.height * 2, gapped.height)
    }

    /**
 * Placement / clipping regression guard (review round 1, P1). For degree=0 and no gap the cell
 * Width equals the measured text width. `MultiParagraph.paint` draws from the canvas origin, so * the text box must be centred in the cell by translating its origin to
 * `(finalWidth - textWidth)/2` — NOT to `finalWidth/2`. Translating to `finalWidth/2` would
 * paint the paragraph starting at the cell centre and clip its right half.
 *
 * This asserts the visible-pixel column span crosses the cell's vertical centre line: there must
 * be visible columns strictly LEFT of `width/2` AND strictly RIGHT of `width/2`. The old
 * (buggy) `translate(left = finalWidth/2)` placement left all visible columns `>= width/2`
 * (everything painted from the centre rightward), so it fails this guard.
     */
    @Test
    fun text_is_box_centred_and_not_clipped_at_degree_0() {
        val content = WatermarkTextContent(
            text = "GOLDEN",
            style = TextStyle(fontSize = 24.sp),
            color = Color.White,
        )
        val cell = WatermarkCellComposer.composeTextCell(
            env, content, degree = 0f, hGapPercent = 0, vGapPercent = 0,
        )
        val pixels = cell.toPixelMap()
        val midX = cell.width / 2
        var leftOfCenter = false
        var rightOfCenter = false
        for (y in 0 until pixels.height) {
            for (x in 0 until pixels.width) {
                if (pixels[x, y].alpha > 0) {
                    if (x < midX) leftOfCenter = true
                    if (x > midX) rightOfCenter = true
                }
            }
        }
        assertTrue(leftOfCenter, "text must have visible pixels LEFT of the cell centre (not painted from centre onward)")
        assertTrue(rightOfCenter, "text must have visible pixels RIGHT of the cell centre (not clipped)")
    }

    /**
 * Lock (root cause #1): with `TextAlign.Center` baked into the measured style, a * SHORT first line in a multiline cell must be **horizontally centred**, not left-aligned at the
 * box edge. Uses a deliberately narrow top line ("I") over a wide bottom line so the difference is
 * unambiguous: before the fix the top line's ink sat flush-left (centre ≈ small x); after it, the
 * top line's ink is centred in the cell (centre ≈ cell mid, with a left margin). Mirrors the
 * Android CENTER `TextPaint` behaviour the renderer uses for multiline.
     */
    @Test
    fun multiline_short_line_is_horizontally_centred() {
        val content = WatermarkTextContent(
            text = "I\nWWWWWWWWWW",
            style = TextStyle(fontSize = 24.sp),
            color = Color.White,
        )
        val cell = WatermarkCellComposer.composeTextCell(env, content, degree = 0f, hGapPercent = 0, vGapPercent = 0)
        val px = cell.toPixelMap()
        val midX = cell.width / 2
        // Top third is safely within line 1 of a 2-line, full-box-centred (degree 0) cell.
        val topBand = (cell.height / 3).coerceAtLeast(1)
        var minX = Int.MAX_VALUE
        var maxX = -1
        for (y in 0 until topBand) {
            for (x in 0 until cell.width) {
                if (px[x, y].alpha > 0) {
                    if (x < minX) minX = x
                    if (x > maxX) maxX = x
                }
            }
        }
        assertTrue(maxX >= 0, "short top line must render visible pixels")
        val centreX = (minX + maxX) / 2
        val tol = (cell.width / 8).coerceAtLeast(2)
        assertTrue(
            kotlin.math.abs(centreX - midX) <= tol,
            "short top line must be horizontally centred (centreX=$centreX vs midX=$midX, tol=$tol)",
        )
        assertTrue(
            minX > tol,
            "short top line must have a left margin (centred, not Start-aligned): minX=$minX, width=${cell.width}",
        )
    }

    /**
 * (owner-approved C2, test-only): proves the commonMain text raster renders **CJK** with the
 * **bundled Noto Sans SC** font on the JVM/Skiko host — the cross-platform value of the bundle (the
 * Desktop system font may lack full CJK; the bundled SC guarantees coverage). The font is injected * via `WatermarkTextContent.style.fontFamily = bundledLatinCjkFontFamily()` through the existing
 * `TextRasterEnv` boundary (no compose-resources / CMP-9547). NOT a production path.
     */
    @Test
    fun cjk_cell_renders_with_bundled_font() {
        val content = WatermarkTextContent(
            text = "请勿转载",
            // CJK-first so Han glyphs are guaranteed to resolve from Noto Sans SC (a Compose FontFamily
            // does not guarantee per-glyph fallback from a Latin-first face to the CJK face); this test
            // only proves the bundle CAN render CJK on the JVM host. Order/fallback behaviour for the
            // Latin+CJK case is measured/reported by the Android parity test ( round 2).
            style = TextStyle(fontSize = 24.sp, fontFamily = bundledLatinCjkFontFamily(latinFirst = false)),
            color = Color.White,
        )
        val cell = WatermarkCellComposer.composeTextCell(env, content, degree = 0f)
        assertTrue(cell.width > 0 && cell.height > 0, "bundled CJK cell must have positive dims")
        val px = cell.toPixelMap()
        var nonBlank = 0
        for (y in 0 until px.height) for (x in 0 until px.width) if (px[x, y].alpha > 0) nonBlank++
        assertTrue(nonBlank > 0, "bundled CJK text must render visible pixels on the JVM host")
    }

    @Test
    fun oversized_multiline_text_cell_caps_backing_bitmap_and_preserves_logical_dims() {
        val content = WatermarkTextContent(
            text = "OVERSIZED WATERMARK CELL\nSECOND LONG LINE FOR BOUNDS",
            style = TextStyle(fontSize = 180.sp),
            color = Color.White,
        )
        val measurer = androidx.compose.ui.text.TextMeasurer(
            env.fontFamilyResolver,
            env.density,
            env.layoutDirection,
        )
        val initial = measurer.measure(
            androidx.compose.ui.text.AnnotatedString(content.text),
            style = content.style,
        )
        val measured = if (initial.lineCount > 1) {
            measurer.measure(
                androidx.compose.ui.text.AnnotatedString(content.text),
                style = content.style.copy(textAlign = androidx.compose.ui.text.style.TextAlign.Center),
            )
        } else {
            initial
        }
        val degree = 315f
        val fixW = WatermarkGeometry.rotatedCellWidth(
            measured.size.width.toFloat().coerceAtLeast(1f),
            measured.size.height.toFloat().coerceAtLeast(1f),
            degree,
        )
        val fixH = WatermarkGeometry.rotatedCellHeight(
            measured.size.width.toFloat().coerceAtLeast(1f),
            measured.size.height.toFloat().coerceAtLeast(1f),
            degree,
        )
        val expectedLogicalW = WatermarkGeometry.horizontalGap(fixW.toInt(), 500)
        val expectedLogicalH = WatermarkGeometry.verticalGap(fixH.toInt(), 500)

        val raster = WatermarkCellComposer.composeTextCellRaster(
            env = env,
            content = content,
            degree = degree,
            hGapPercent = 500,
            vGapPercent = 500,
        )
        assertTrue(
            expectedLogicalW > WatermarkCellComposer.MAX_CELL_RASTER_EDGE_PX &&
                expectedLogicalH > WatermarkCellComposer.MAX_CELL_RASTER_EDGE_PX,
            "fixture must exercise oversized text cell (was ${expectedLogicalW}x$expectedLogicalH)",
        )
        assertEquals(expectedLogicalW, raster.logicalWidth)
        assertEquals(expectedLogicalH, raster.logicalHeight)
        assertTrue(
            raster.bitmap.width <= WatermarkCellComposer.MAX_CELL_RASTER_EDGE_PX &&
                raster.bitmap.height <= WatermarkCellComposer.MAX_CELL_RASTER_EDGE_PX,
            "backing bitmap must cap at ${WatermarkCellComposer.MAX_CELL_RASTER_EDGE_PX}, " +
                "got ${raster.bitmap.width}x${raster.bitmap.height}",
        )
        val px = raster.bitmap.toPixelMap()
        var nonTransparent = 0
        for (y in 0 until px.height) {
            for (x in 0 until px.width) {
                if (px[x, y].alpha > 0f) nonTransparent++
            }
        }
        assertTrue(nonTransparent > 0, "oversized text cell must render visible pixels")
        assertEquals(Color.Transparent, px[0, 0], "gap corner must remain transparent")
    }

    @Test
    fun sparse_gap_high_res_text_cell_keeps_sharp_draw_box_and_aligns_center() {
        val imageWidth = 4000
        val fontPx = WatermarkGeometry.fontPx(textSize = 24f, imageWidth = imageWidth)
        val content = WatermarkTextContent(
            text = "EASY WATERMARK",
            style = TextStyle(fontSize = fontPx.sp),
            color = Color.White,
        )
        val degree = 315f
        val hGap = 400
        val vGap = 400
        val raster = WatermarkCellComposer.composeTextCellRaster(
            env = env,
            content = content,
            degree = degree,
            hGapPercent = hGap,
            vGapPercent = vGap,
        )

        assertTrue(
            raster.logicalWidth > WatermarkCellComposer.MAX_CELL_RASTER_EDGE_PX &&
                raster.logicalHeight > WatermarkCellComposer.MAX_CELL_RASTER_EDGE_PX,
            "sparse-gap 12MP fixture must exceed 2048 logically (was ${raster.logicalWidth}x${raster.logicalHeight})",
        )
        assertTrue(
            raster.drawWidth < WatermarkCellComposer.MAX_CELL_RASTER_EDGE_PX &&
                raster.drawHeight < WatermarkCellComposer.MAX_CELL_RASTER_EDGE_PX,
            "content draw box must fit below 2048 (was ${raster.drawWidth}x${raster.drawHeight})",
        )
        assertEquals(
            raster.drawWidth,
            raster.bitmap.width,
            "sparse-gap text glyphs must raster 1:1 without downscaling",
        )
        assertEquals(
            raster.drawHeight,
            raster.bitmap.height,
            "sparse-gap text glyphs must raster 1:1 without downscaling",
        )
        assertTrue(raster.bitmap.width < raster.logicalWidth)
        assertTrue(raster.bitmap.height < raster.logicalHeight)

        val cellPx = raster.bitmap.toPixelMap()
        assertEquals(Color.Transparent, cellPx[0, 0], "4 px safety margin must keep corner transparent")

        val bg = ImageBitmap(raster.logicalWidth, raster.logicalHeight, ImageBitmapConfig.Argb8888)
        CanvasDrawScope().draw(
            density = Density(1f),
            layoutDirection = LayoutDirection.Ltr,
            canvas = Canvas(bg),
            size = Size(raster.logicalWidth.toFloat(), raster.logicalHeight.toFloat()),
        ) {
            drawRect(Color.Black)
        }
        val composed = WatermarkCellComposer.composeOverBackground(
            background = bg,
            cell = raster.bitmap,
            tileMode = WatermarkTileMode.REPEAT,
            offsetX = 0f,
            offsetY = 0f,
            alpha = 1f,
            tileWidth = raster.logicalWidth,
            tileHeight = raster.logicalHeight,
            drawWidth = raster.drawWidth,
            drawHeight = raster.drawHeight,
        )

        val measurer = TextMeasurer(env.fontFamilyResolver, env.density, env.layoutDirection)
        val initial = measurer.measure(AnnotatedString(content.text), style = content.style)
        val layout = if (initial.lineCount > 1) {
            measurer.measure(
                AnnotatedString(content.text),
                style = content.style.copy(textAlign = TextAlign.Center),
            )
        } else {
            initial
        }
        val textW = layout.size.width.toFloat().coerceAtLeast(1f)
        val textH = layout.size.height.toFloat().coerceAtLeast(1f)
        val manualCell = ImageBitmap(raster.logicalWidth, raster.logicalHeight, ImageBitmapConfig.Argb8888)
        CanvasDrawScope().draw(
            density = env.density,
            layoutDirection = env.layoutDirection,
            canvas = Canvas(manualCell),
            size = Size(raster.logicalWidth.toFloat(), raster.logicalHeight.toFloat()),
        ) {
            rotate(degrees = degree, pivot = Offset(raster.logicalWidth / 2f, raster.logicalHeight / 2f)) {
                translate(
                    left = (raster.logicalWidth - textW) / 2f,
                    top = (raster.logicalHeight - textH) / 2f,
                ) {
                    drawIntoCanvas { canvas ->
                        layout.multiParagraph.paint(
                            canvas = canvas,
                            color = content.color,
                            drawStyle = content.style.drawStyle,
                        )
                    }
                }
            }
        }
        val manual = ImageBitmap(raster.logicalWidth, raster.logicalHeight, ImageBitmapConfig.Argb8888)
        CanvasDrawScope().draw(
            density = Density(1f),
            layoutDirection = LayoutDirection.Ltr,
            canvas = Canvas(manual),
            size = Size(raster.logicalWidth.toFloat(), raster.logicalHeight.toFloat()),
        ) {
            drawImage(bg, topLeft = Offset.Zero)
            drawImage(manualCell, topLeft = Offset.Zero)
        }

        val compPx = composed.toPixelMap()
        val manPx = manual.toPixelMap()
        assertEquals(Color.Black, compPx[0, 0], "outer gap corner must remain background")
        var compInk = 0
        var manInk = 0
        var compSumX = 0.0
        var compSumY = 0.0
        var manSumX = 0.0
        var manSumY = 0.0
        var compMinX = Int.MAX_VALUE
        var compMaxX = -1
        var compMinY = Int.MAX_VALUE
        var compMaxY = -1
        var manMinX = Int.MAX_VALUE
        var manMaxX = -1
        var manMinY = Int.MAX_VALUE
        var manMaxY = -1
        var maxDelta = 0f
        for (y in 0 until raster.logicalHeight) {
            for (x in 0 until raster.logicalWidth) {
                val cc = compPx[x, y]
                val mc = manPx[x, y]
                maxDelta = max(maxDelta, abs(cc.red - mc.red))
                if (cc.red > 0.05f) {
                    compInk++
                    compSumX += x
                    compSumY += y
                    compMinX = min(compMinX, x)
                    compMaxX = max(compMaxX, x)
                    compMinY = min(compMinY, y)
                    compMaxY = max(compMaxY, y)
                }
                if (mc.red > 0.05f) {
                    manInk++
                    manSumX += x
                    manSumY += y
                    manMinX = min(manMinX, x)
                    manMaxX = max(manMaxX, x)
                    manMinY = min(manMinY, y)
                    manMaxY = max(manMaxY, y)
                }
            }
        }
        assertTrue(compInk > 0 && manInk > 0, "both composed and manual renders must draw text")
        assertTrue(
            abs(compSumX / compInk - manSumX / manInk) < 0.25 &&
                abs(compSumY / compInk - manSumY / manInk) < 0.25,
            "glyph centroid must align identically at center",
        )
        assertTrue(
            abs(compMinX - manMinX) <= 1 && abs(compMaxX - manMaxX) <= 1 &&
                abs(compMinY - manMinY) <= 1 && abs(compMaxY - manMaxY) <= 1,
            "glyph bounding box must match un-capped render",
        )
        assertTrue(
            maxDelta <= 0.02f,
            "1:1 drawWidth glyph pixels must match un-capped manual draw without downscale blur (maxDelta=$maxDelta)",
        )
    }
}
