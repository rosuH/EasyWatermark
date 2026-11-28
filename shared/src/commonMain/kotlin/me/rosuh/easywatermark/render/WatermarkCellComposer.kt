package me.rosuh.easywatermark.render

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.ImageBitmapConfig
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import me.rosuh.easywatermark.data.model.WatermarkTileMode
import kotlin.math.roundToInt

/**
 * Offscreen raster for a single watermark cell plus its un-capped logical dimensions (px) and
 * centered content draw box ([drawWidth] × [drawHeight]).
 *
 * Oversized cells keep [bitmap] bounded (≤ [WatermarkCellComposer.MAX_CELL_RASTER_EDGE_PX]) so
 * Android `RecordingCanvas` never receives >100 MiB bitmaps, while [logicalWidth] × [logicalHeight]
 * preserve exact tiling pitch and hit-testing geometry. When gap margins push logical dims past
 * [WatermarkCellComposer.MAX_CELL_RASTER_EDGE_PX], [drawWidth] × [drawHeight] keep rasterization
 * tight to the centered rotated content box so glyphs/icons avoid unnecessary downscale blur.
 */
data class RasteredWatermarkCell(
    val bitmap: ImageBitmap,
    val logicalWidth: Int = bitmap.width,
    val logicalHeight: Int = bitmap.height,
    val drawWidth: Int = logicalWidth,
    val drawHeight: Int = logicalHeight,
    val contentWidth: Float = drawWidth.toFloat(),
    val contentHeight: Float = drawHeight.toFloat(),
) {
    init {
        require(logicalWidth >= 1 && logicalHeight >= 1) {
            "RasteredWatermarkCell logical dims must be >= 1 (got ${logicalWidth}x$logicalHeight)"
        }
        require(drawWidth in 1..logicalWidth && drawHeight in 1..logicalHeight) {
            "RasteredWatermarkCell draw dims must be in 1..logical (got ${drawWidth}x$drawHeight vs ${logicalWidth}x$logicalHeight)"
        }
        require(contentWidth.isFinite() && contentWidth > 0f && contentHeight.isFinite() && contentHeight > 0f) {
            "RasteredWatermarkCell content dims must be finite and > 0 (got ${contentWidth}x$contentHeight)"
        }
    }
}

/**
 * CommonMain offscreen watermark **cell** primitives (Compose Multiplatform graphics).
 *
 * Builds one text or icon cell ([composeTextCell] / [composeIconCell]) sized with [WatermarkGeometry]
 * (rotated AABB + gaps), then tiles it over a background ([composeOverBackground]). Production
 * Android (`AndroidCommonRaster`), Desktop, and iOS share this path (ADR-0018). Image decode stays
 * On the platform edge — pass an already-decoded [ImageBitmap] for icons. *
 * Native Android `WatermarkRenderer` is measurement/golden oracle only (no byte-parity claim).
 */
object WatermarkCellComposer {

    /**
     * Hard per-axis backing bitmap cap: 2048×2048 ARGB_8888 = 16 MiB, well below Android's
     * 100 MiB `RecordingCanvas` bitmap limit and common `GL_MAX_TEXTURE_SIZE` (4096).
     */
    const val MAX_CELL_RASTER_EDGE_PX: Int = 2048

    private const val MAX_LOGICAL_CELL_EDGE_PX: Int = 1 shl 24
    private const val CONTENT_BOX_SAFETY_PAD_PX: Int = 8 // 4 px transparent margin per side

    private fun boundedLogicalGap(basePx: Float, gapPercent: Int): Int {
        val safeGap = gapPercent.coerceAtLeast(0)
        val safeBase = if (basePx.isFinite() && basePx >= 1f) basePx else 1f
        return if (safeBase <= 1_000_000f && safeGap <= 10_000) {
            WatermarkGeometry.horizontalGap(safeBase.toInt(), safeGap)
                .coerceIn(1, MAX_LOGICAL_CELL_EDGE_PX)
        } else {
            (safeBase.toDouble() * ((safeGap.toDouble() / 100.0) + 1.0))
                .toLong()
                .coerceIn(1L, MAX_LOGICAL_CELL_EDGE_PX.toLong())
                .toInt()
        }
    }

    internal fun centeredContentBoxDimension(contentSpanPx: Float, logicalDim: Int): Int {
        val safeLogical = logicalDim.coerceAtLeast(1)
        val safeSpan = if (contentSpanPx.isFinite()) contentSpanPx.coerceAtLeast(1f) else safeLogical.toFloat()
        val raw = (kotlin.math.ceil(safeSpan.toDouble()).toLong() + CONTENT_BOX_SAFETY_PAD_PX)
            .coerceIn(1L, safeLogical.toLong())
            .toInt()
        // Match parity with logicalDim so (logicalDim - drawDim) / 2 is an exact integer (zero 0.5 px blur).
        return if ((safeLogical - raw) % 2 != 0 && raw < safeLogical) raw + 1 else raw
    }

    private fun tightDrawDimensionsFor(
        contentSpanW: Float,
        contentSpanH: Float,
        logicalWidth: Int,
        logicalHeight: Int,
        backgroundColor: Color,
    ): Pair<Int, Int> =
        if (backgroundColor == Color.Transparent &&
            (logicalWidth > MAX_CELL_RASTER_EDGE_PX || logicalHeight > MAX_CELL_RASTER_EDGE_PX)
        ) {
            centeredContentBoxDimension(contentSpanW, logicalWidth) to
                centeredContentBoxDimension(contentSpanH, logicalHeight)
        } else {
            logicalWidth to logicalHeight
        }

    internal fun rasterDimensionsFor(
        logicalWidth: Int,
        logicalHeight: Int,
        maxEdgePx: Int = MAX_CELL_RASTER_EDGE_PX,
    ): Pair<Int, Int> {
        val safeW = logicalWidth.coerceAtLeast(1)
        val safeH = logicalHeight.coerceAtLeast(1)
        val safeMax = maxEdgePx.coerceAtLeast(1)
        if (safeW <= safeMax && safeH <= safeMax) return safeW to safeH
        val scale = safeMax.toDouble() / maxOf(safeW, safeH).toDouble()
        return (safeW * scale).roundToInt().coerceIn(1, safeMax) to
            (safeH * scale).roundToInt().coerceIn(1, safeMax)
    }

    /**
     * Compose one rotated, gap-spaced watermark cell into an offscreen [ImageBitmap].
     *
     * @param contentWidth width of the content box (e.g. measured text / scaled icon), px
     * @param contentHeight height of the content box, px
     * @param degree rotation in degrees (matches `WaterMark.degree`)
     * @param hGapPercent horizontal gap percent (0 -> adjacent, 100 -> 2x); matches `WaterMark.hGap`
     * @param vGapPercent vertical gap percent; matches `WaterMark.vGap`
     * @param contentColor fill of the content rect (placeholder until the text/icon raster lands)
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
    ): ImageBitmap = composeRotatedCellRaster(
        contentWidth = contentWidth,
        contentHeight = contentHeight,
        degree = degree,
        hGapPercent = hGapPercent,
        vGapPercent = vGapPercent,
        contentColor = contentColor,
        backgroundColor = backgroundColor,
    ).bitmap

    /**
     * Compose one rotated, gap-spaced watermark cell into a bounded [RasteredWatermarkCell].
     */
    fun composeRotatedCellRaster(
        contentWidth: Int,
        contentHeight: Int,
        degree: Float,
        hGapPercent: Int = 0,
        vGapPercent: Int = 0,
        contentColor: Color = Color.White,
        backgroundColor: Color = Color.Transparent,
    ): RasteredWatermarkCell {
        val cw = contentWidth.coerceAtLeast(1).toFloat()
        val ch = contentHeight.coerceAtLeast(1).toFloat()

        // SAME sizing math as WatermarkRenderer (Android): rotated-AABB then gap expansion.
        val fixWidth = WatermarkGeometry.rotatedCellWidth(cw, ch, degree)
        val fixHeight = WatermarkGeometry.rotatedCellHeight(cw, ch, degree)
        val finalWidth = boundedLogicalGap(fixWidth, hGapPercent)
        val finalHeight = boundedLogicalGap(fixHeight, vGapPercent)
        val (drawWidth, drawHeight) = tightDrawDimensionsFor(
            contentSpanW = fixWidth,
            contentSpanH = fixHeight,
            logicalWidth = finalWidth,
            logicalHeight = finalHeight,
            backgroundColor = backgroundColor,
        )
        val (rasterWidth, rasterHeight) = rasterDimensionsFor(drawWidth, drawHeight)

        val bitmap = ImageBitmap(rasterWidth, rasterHeight, ImageBitmapConfig.Argb8888)
        CanvasDrawScope().draw(
            density = Density(1f),
            layoutDirection = LayoutDirection.Ltr,
            canvas = Canvas(bitmap),
            size = Size(rasterWidth.toFloat(), rasterHeight.toFloat()),
        ) {
            if (backgroundColor != Color.Transparent) {
                drawRect(color = backgroundColor)
            }
            val drawRotatedContent: DrawScope.() -> Unit = {
                // Rotate about the draw-box centre; mirrors `canvas.rotate(degree, finalW/2, finalH/2)`.
                rotate(degrees = degree, pivot = Offset(drawWidth / 2f, drawHeight / 2f)) {
                    // Content box centred in the draw box (placeholder; text/icon raster is the next slice).
                    drawRect(
                        color = contentColor,
                        topLeft = Offset((drawWidth - cw) / 2f, (drawHeight - ch) / 2f),
                        size = Size(cw, ch),
                    )
                }
            }
            if (rasterWidth == drawWidth && rasterHeight == drawHeight) {
                drawRotatedContent()
            } else {
                scale(
                    scaleX = rasterWidth.toFloat() / drawWidth.toFloat(),
                    scaleY = rasterHeight.toFloat() / drawHeight.toFloat(),
                    pivot = Offset.Zero,
                ) {
                    drawRotatedContent()
                }
            }
        }
        return RasteredWatermarkCell(
            bitmap = bitmap,
            logicalWidth = finalWidth,
            logicalHeight = finalHeight,
            drawWidth = drawWidth,
            drawHeight = drawHeight,
            contentWidth = cw,
            contentHeight = ch,
        )
    }

    /**
 * Compose ONE rotated, gap-spaced watermark **text** cell into an offscreen [ImageBitmap] * — the commonMain text-raster analogue of the Android `WatermarkRenderer.buildTextShader` text
 * path. The platform font bootstrap is the injected [env] (see [TextRasterEnv]); this function
 * never constructs a `FontFamily.Resolver` itself, so no Android `Context` / Skiko default leaks
 * into commonMain.
 *
 * Pipeline (mirrors the Android text cell 1:1):
 * 1. **Measure** [content] with `TextMeasurer(env.fontFamilyResolver, env.density,
 * env.layoutDirection).measure(...)` — the SAME measurement path the Android
 * `WatermarkTextMeasurer` uses (S3b), so the cell box is computed identically.
 * 2. **Size** the cell via the shared [WatermarkGeometry] (rotated-AABB + gap), identical math
 * to `WatermarkRenderer.buildTextShader`.
 * 3. **Draw** onto an offscreen [ImageBitmap] via [CanvasDrawScope], rotating about the cell
 * centre, then **raster** the text with `layoutResult.multiParagraph.paint(Canvas(bitmap),
 * color)` — the commonMain equivalent of `StaticLayout.draw(canvas)`.
 *
 * Placement: the text box is translated to **`(finalWidth - textWidth)/2` horizontally and
 * `(finalHeight - textHeight)/2` vertically** to centre the measured box inside the cell, and for
 * **multiline** text (`lineCount > 1`) the measured style uses **`TextAlign.Center`** so
 * **each line is centred within that box** — reproducing the Android `buildTextShader` placement,
 * which draws a CENTER-aligned `TextPaint` `StaticLayout` translated to `canvas.translate(finalW/2,
 * …)`. This fixes the horizontal divergence (shorter multiline rows previously left-aligned
 * at the box edge instead of centred: `multiline` band-centre 43 → 80 on device). Single-line text
 * keeps the default alignment — Center is logically a no-op there but perturbs a single ROTATED
 * line sub-pixel (the rotated emoji default regressed under blanket Center), and single-line must
 * stay byte-identical. The vertical offset stays full-box centred and is NOT changed to
 * Android's line-0-based offset/clip (root cause #2, deferred to an owner decision — /).
 *
 * Production (ADR-0018): used on Android (via [AndroidCommonRaster]) and Desktop/iOS. Cross-
 * platform / native-oracle pixel parity vs `StaticLayout` is **not** asserted (CJK/engine delta
 * expected). Unit coverage: `WatermarkCellComposerTest` on `:shared:desktopTest`.
 *
 * @param env the platform-injected text bootstrap (resolver + density + layout dir)
 * @param content the text content to measure + paint (text + TextStyle + fill colour)
 * @param degree rotation in degrees (matches `WaterMark.degree`)
 * @param hGapPercent horizontal gap percent (0 -> adjacent, 100 -> 2x); matches `WaterMark.hGap`
 * @param vGapPercent vertical gap percent; matches `WaterMark.vGap`
 * @param backgroundColor cell background (transparent by default, like the production cell)
     */
    fun composeTextCell(
        env: TextRasterEnv,
        content: WatermarkTextContent,
        degree: Float,
        hGapPercent: Int = 0,
        vGapPercent: Int = 0,
        backgroundColor: Color = Color.Transparent,
    ): ImageBitmap = composeTextCellRaster(
        env = env,
        content = content,
        degree = degree,
        hGapPercent = hGapPercent,
        vGapPercent = vGapPercent,
        backgroundColor = backgroundColor,
    ).bitmap

    /**
     * Compose ONE rotated, gap-spaced watermark **text** cell into a bounded [RasteredWatermarkCell].
     */
    fun composeTextCellRaster(
        env: TextRasterEnv,
        content: WatermarkTextContent,
        degree: Float,
        hGapPercent: Int = 0,
        vGapPercent: Int = 0,
        backgroundColor: Color = Color.Transparent,
    ): RasteredWatermarkCell {
        require(content.text.isNotEmpty()) { "composeTextCell requires non-empty text" }
        // CMP Skiko ParagraphBuilder.makeSkTextStyle keeps a process-wide WeakKeysCache.
        // Two Dispatchers.Default workers (iOS progressive import + overlay) race
        // filterInPlace vs Key.ref and SIGSEGV — iosApp-2026-08-29-015756.ips.
        // The cell is a function of config; parallel compose is duplicate work.
        return withComposeTextCellLock {
            composeTextCellLocked(env, content, degree, hGapPercent, vGapPercent, backgroundColor)
        }
    }

    private fun composeTextCellLocked(
        env: TextRasterEnv,
        content: WatermarkTextContent,
        degree: Float,
        hGapPercent: Int,
        vGapPercent: Int,
        backgroundColor: Color,
    ): RasteredWatermarkCell {
        // 1) Measure the text box (same path as the Android WatermarkTextMeasurer, S3b).
        // NB positional args: the commonMain TextMeasurer constructor parameters are the
        // `default*` overridable form (defaultFontFamilyResolver / defaultDensity /
        // defaultLayoutDirection), so named args would not resolve; the Android
        // WatermarkTextMeasurer uses the same positional form.
        val measurer = TextMeasurer(
            env.fontFamilyResolver,
            env.density,
            env.layoutDirection,
        )
        // (root cause #1): center EACH line within the measured paragraph box for
        // MULTILINE text, matching the Android renderer's CENTER `TextPaint` (`PainKtx.applyConfig`) +
        // `StaticLayout` behaviour. Under unbounded measurement the paragraph width == the widest line,
        // so `TextAlign.Center` centers shorter lines within that box (e.g. "DO NOT" over "REDISTRIBUTE").
        // Applied ONLY when `lineCount > 1`: for a single line Center is logically a no-op, but it is NOT
        // pixel-free in practice — it perturbs sub-pixel placement of a single rotated line (measured on
        // device: the rotated emoji default regressed under blanket Center), and requires
        // single-line to stay byte-identical. So a first measure detects multiline; only then is the
        // SAME `centeredStyle` re-measured + painted. Does NOT touch the vertical offset / clipping
        // (root cause #2 — owner decision, out of scope).
        val initial = measurer.measure(text = AnnotatedString(content.text), style = content.style)
        val layout = if (initial.lineCount > 1) {
            measurer.measure(
                text = AnnotatedString(content.text),
                style = content.style.copy(textAlign = TextAlign.Center),
            )
        } else {
            initial
        }
        val textWidth = layout.size.width.toFloat().coerceAtLeast(1f)
        val textHeight = layout.size.height.toFloat().coerceAtLeast(1f)

        // 2) Cell size: rotated-AABB + gap (identical math to WatermarkRenderer.buildTextShader).
        val fixWidth = WatermarkGeometry.rotatedCellWidth(textWidth, textHeight, degree)
        val fixHeight = WatermarkGeometry.rotatedCellHeight(textWidth, textHeight, degree)
        val finalWidth = boundedLogicalGap(fixWidth, hGapPercent)
        val finalHeight = boundedLogicalGap(fixHeight, vGapPercent)
        val (drawWidth, drawHeight) = tightDrawDimensionsFor(
            contentSpanW = fixWidth,
            contentSpanH = fixHeight,
            logicalWidth = finalWidth,
            logicalHeight = finalHeight,
            backgroundColor = backgroundColor,
        )
        val (rasterWidth, rasterHeight) = rasterDimensionsFor(drawWidth, drawHeight)

        // 3) Offscreen raster: rotate about the draw-box centre, then paint the text centred.
        val bitmap = ImageBitmap(rasterWidth, rasterHeight, ImageBitmapConfig.Argb8888)
        CanvasDrawScope().draw(
            density = env.density,
            layoutDirection = env.layoutDirection,
            canvas = Canvas(bitmap),
            size = Size(rasterWidth.toFloat(), rasterHeight.toFloat()),
        ) {
            if (backgroundColor != Color.Transparent) {
                drawRect(color = backgroundColor)
            }
            val drawTextBlock: DrawScope.() -> Unit = {
                // Mirrors `canvas.rotate(degree, finalW/2, finalH/2)` then
                // `canvas.translate(finalW/2, (finalH - lineHeight) / 2)` from the Android renderer
                // (whose TextPaint is CENTER-aligned). Here we rotate about the draw-box centre, then
                // centre the measured TEXT BOX inside the draw box by translating its top-left to
                // ((drawWidth - textWidth)/2, (drawHeight - textHeight)/2). MultiParagraph.paint draws
                // from the canvas origin (top-left of the text box), so this box-centring — NOT a
                // translate to drawWidth/2 — is what places the text correctly; translating to
                // drawWidth/2 would paint the paragraph starting at the cell centre and clip the right
                // half for degree=0/no-gap (where drawWidth == textWidth). Using the DrawScope transform
                // stack (rotate -> translate -> drawIntoCanvas) keeps the platform canvas balanced,
                // instead of hand-managing canvas.save/restore.
                rotate(degrees = degree, pivot = Offset(drawWidth / 2f, drawHeight / 2f)) {
                    translate(
                        left = (drawWidth - textWidth) / 2f,
                        top = (drawHeight - textHeight) / 2f,
                    ) {
                        // drawIntoCanvas reaches the underlying commonMain Canvas; multiParagraph.paint
                        // paints the laid-out text from the (now box-centred) canvas origin. The layout was
                        // measured with TextAlign.Center, so each line is also centred WITHIN the
                        // box — matching the Android CENTER TextPaint for multiline.
                        drawIntoCanvas { canvas ->
                            // Pass the measured drawStyle so Fill/Stroke is honored (null/Fill render alike).
                            layout.multiParagraph.paint(
                                canvas = canvas,
                                color = content.color,
                                drawStyle = content.style.drawStyle,
                            )
                        }
                    }
                }
            }
            if (rasterWidth == drawWidth && rasterHeight == drawHeight) {
                drawTextBlock()
            } else {
                scale(
                    scaleX = rasterWidth.toFloat() / drawWidth.toFloat(),
                    scaleY = rasterHeight.toFloat() / drawHeight.toFloat(),
                    pivot = Offset.Zero,
                ) {
                    drawTextBlock()
                }
            }
        }
        return RasteredWatermarkCell(
            bitmap = bitmap,
            logicalWidth = finalWidth,
            logicalHeight = finalHeight,
            drawWidth = drawWidth,
            drawHeight = drawHeight,
            contentWidth = textWidth,
            contentHeight = textHeight,
        )
    }

    /** Android `buildIconShader` derives the icon scale from `config.textSize / 14f` (14 ⇒ 1×). */
    const val ICON_SCALE_REFERENCE_TEXT_SIZE: Float = 14f

    /**
 * Compose ONE rotated, gap-spaced watermark **icon/image** cell into an offscreen * [ImageBitmap] — the commonMain analogue of the Android `WatermarkRenderer.buildIconShader`.
 *
 * Takes an **already-decoded** Compose [icon] [ImageBitmap]; **decode is NOT done here**
 * (`Uri`/`ContentResolver`/file bytes/EXIF/downsample stay a per-platform boundary — see the
 * `decode-boundary.md`), so no `android.graphics.*` leaks into commonMain.
 *
 * Pipeline (mirrors the Android icon cell 1:1, `WatermarkRenderer.kt:187-234`):
 * 1. raw dims = `icon.width`/`height`, each coerced to ≥ 1;
 * 2. base cell is a **square** of side `WatermarkGeometry.diagonal(rawHeight, rawWidth)`
 * (`diagonal` is symmetric; the arg order mirrors the Android call), expanded by the gap
 * percents — identical to `buildIconShader`;
 * 3. apply [scaleRatio] (production: `textSize / 14f`, see [ICON_SCALE_REFERENCE_TEXT_SIZE]) to
 * both the cell and the drawn image;
 * 4. rotate about the cell centre and draw the image **scaled to `rawW*scale × rawH*scale`,
 * centred** in the cell.
 *
 * Placement: pivot and centring follow Android's **float** placement *math* — the
 * un-truncated cell extent `finalWidth * scaleRatio` — rotating about `Offset(cellW/2, cellH/2)`
 * and placing the scaled image at the float top-left `((cellW - scaledWidth)/2,
 * (cellH - scaledHeight)/2)` (vs the earlier integer-truncated pivot/offset). This matches the
 * *placement geometry* of `canvas.rotate(degree, finalW*scaleRatio/2, …); drawBitmap(scaled,
 * (finalW*scaleRatio - scaled.width)/2f, …)`. The cell is still ALLOCATED at the integer
 * `(finalW*scaleRatio).toInt()` (a bitmap is integer-sized, as is Android's `targetBitmap`).
 *
 * **This does NOT make the commonMain raster byte-identical to Android for rotated non-uniform
 * icons.** Float placement is *more correct* and reduced the strict-golden delta on
 * `icon_rot_315` (16 → 5 / 1936 px), but a residual remains: the rotated point-draw used here
 * (`drawImage(topLeft)`) has no `filterQuality` parameter, so the rotation resamples at the
 * DrawScope default (not guaranteed nearest), whereas Android draws with a bare nearest paint. No
 * commonMain `drawImage` overload offers float placement AND nearest filtering together (the
 * `dstSize` overload is nearest but rect-maps with a half-texel offset; only the platform
 * `nativeCanvas.drawBitmap` has both). Production icons still go through this common path on
 * Android (ADR-0018); residual vs native oracle is accepted — **not** byte-identical for
 * rotated non-uniform icons (historical → measurement).
 *
 * Scaling: Android native oracle pre-scales with `Bitmap.createScaledBitmap(..., filter=false)`
 * (nearest-neighbor). The commonMain path mirrors the *intent* — pre-scale via
 * `drawImage(dstSize = …, filterQuality = FilterQuality.None)` (ADR-0014 "icon filter=false") —
 * but is NOT asserted byte-identical to the native oracle for rotated non-uniform icons.
 *
 * Opacity: native oracle draws with `Paint.alpha = WaterMark.alpha` (0..255). commonMain takes
 * normalized [alpha] in `0f..1f` (clamped) for `drawImage(alpha = …)`. Platform adapters pass
 * `WaterMark.alpha / 255f` (`128` ⇒ `0.5f`). Default `1f` matches default `WaterMark.alpha = 255`;
 * production callers should pass the configured alpha.
 *
 * Bootstrap safety guard: Android allocates `(finalWidth*scaleRatio).toInt()` with no explicit
 * min and would crash on a 0-size bitmap; this commonMain bootstrap coerces target/scaled dims to
 * ≥ 1 (documented delta — it only affects degenerate `scaleRatio→0` / 0-size inputs).
 *
 * Production (ADR-0018): Android preview/export use this via [AndroidCommonRaster] as well as
 * Desktop/iOS. Byte-exact parity with native `buildIconShader` is **not** claimed (placement
 * note above). Unit coverage: `:shared:desktopTest` (`WatermarkIconCellRasterTest`).
 * Recycled-bitmap rejection is not observable in commonMain — input validity is the
 * caller/decode-boundary responsibility.
 *
 * @param icon the already-decoded source image
 * @param degree rotation in degrees (matches `WaterMark.degree`)
 * @param hGapPercent horizontal gap percent (0 → adjacent, 100 → 2×); matches `WaterMark.hGap`
 * @param vGapPercent vertical gap percent; matches `WaterMark.vGap`
 * @param scaleRatio icon scale (production passes `textSize / ICON_SCALE_REFERENCE_TEXT_SIZE`)
 * @param alpha normalized watermark opacity in `0f..1f` (clamped); the Android adapter
 * passes `WaterMark.alpha / 255f`. Default `1f` is a bootstrap convenience
 * that matches `WaterMark.alpha = 255`; production wiring must pass the
 * configured value, e.g. `128 / 255f`.
     */
    fun composeIconCell(
        icon: ImageBitmap,
        degree: Float,
        hGapPercent: Int = 0,
        vGapPercent: Int = 0,
        scaleRatio: Float = 1f,
        alpha: Float = 1f,
    ): ImageBitmap = composeIconCellRaster(
        icon = icon,
        degree = degree,
        hGapPercent = hGapPercent,
        vGapPercent = vGapPercent,
        scaleRatio = scaleRatio,
        alpha = alpha,
    ).bitmap

    private fun scaleIconNearest(icon: ImageBitmap, targetWidth: Int, targetHeight: Int): ImageBitmap {
        if (targetWidth == icon.width && targetHeight == icon.height) return icon
        val tmp = ImageBitmap(targetWidth, targetHeight, ImageBitmapConfig.Argb8888)
        CanvasDrawScope().draw(
            density = Density(1f),
            layoutDirection = LayoutDirection.Ltr,
            canvas = Canvas(tmp),
            size = Size(targetWidth.toFloat(), targetHeight.toFloat()),
        ) {
            drawImage(
                image = icon,
                srcOffset = IntOffset.Zero,
                srcSize = IntSize(icon.width, icon.height),
                dstOffset = IntOffset.Zero,
                dstSize = IntSize(targetWidth, targetHeight),
                filterQuality = FilterQuality.None,
            )
        }
        return tmp
    }

    /**
     * Compose ONE rotated, gap-spaced watermark **icon/image** cell into a bounded
     * [RasteredWatermarkCell].
     */
    fun composeIconCellRaster(
        icon: ImageBitmap,
        degree: Float,
        hGapPercent: Int = 0,
        vGapPercent: Int = 0,
        scaleRatio: Float = 1f,
        alpha: Float = 1f,
    ): RasteredWatermarkCell {
        val rawWidth = icon.width.coerceAtLeast(1).toFloat()
        val rawHeight = icon.height.coerceAtLeast(1).toFloat()
        val safeScale = if (scaleRatio.isFinite() && scaleRatio > 0f) scaleRatio else 1f
        val safeAlpha = alpha.coerceIn(0f, 1f)

        // SAME math as WatermarkRenderer.buildIconShader: square base cell sized by the content
        // diagonal (arg order mirrors Android; `diagonal` is symmetric), then gap expansion.
        val maxSize = WatermarkGeometry.diagonal(rawHeight, rawWidth)
        val finalWidth = boundedLogicalGap(maxSize.toFloat(), hGapPercent)
        val finalHeight = boundedLogicalGap(maxSize.toFloat(), vGapPercent)

        // Float cell extent (un-truncated) drives pivot + placement, following Android's
        // `finalWidth * scaleRatio` placement math. The logical cell is the integer truncation
        // of it (Android's `targetBitmap` is `(finalWidth*scaleRatio).toInt()` too).
        val cellWidth = (finalWidth.toDouble() * safeScale.toDouble())
            .coerceAtMost(MAX_LOGICAL_CELL_EDGE_PX.toDouble())
            .toFloat()
        val cellHeight = (finalHeight.toDouble() * safeScale.toDouble())
            .coerceAtMost(MAX_LOGICAL_CELL_EDGE_PX.toDouble())
            .toFloat()
        val logicalWidth = cellWidth.toInt().coerceAtLeast(1)
        val logicalHeight = cellHeight.toInt().coerceAtLeast(1)

        if (logicalWidth <= MAX_CELL_RASTER_EDGE_PX && logicalHeight <= MAX_CELL_RASTER_EDGE_PX) {
            val scaledWidth = (rawWidth * safeScale).toInt().coerceAtLeast(1)
            val scaledHeight = (rawHeight * safeScale).toInt().coerceAtLeast(1)

            // Follow Android's TWO-STEP shape: (1) pre-scale the source to scaledWidth×scaledHeight with
            // nearest sampling (the `Bitmap.createScaledBitmap(..., filter=false)` analogue), then (2) draw
            // that pre-scaled image 1:1 at a FLOAT top-left under the FLOAT-pivot rotation (the
            // `canvas.drawBitmap(scaled, x, y, paint)` analogue — a point draw, NOT a src→dst rect map).
            val scaledImage = scaleIconNearest(icon, scaledWidth, scaledHeight)

            val bitmap = ImageBitmap(logicalWidth, logicalHeight, ImageBitmapConfig.Argb8888)
            CanvasDrawScope().draw(
                density = Density(1f),
                layoutDirection = LayoutDirection.Ltr,
                canvas = Canvas(bitmap),
                size = Size(logicalWidth.toFloat(), logicalHeight.toFloat()),
            ) {
                rotate(degrees = degree, pivot = Offset(cellWidth / 2f, cellHeight / 2f)) {
                    drawImage(
                        image = scaledImage,
                        topLeft = Offset(
                            (cellWidth - scaledWidth) / 2f,
                            (cellHeight - scaledHeight) / 2f,
                        ),
                        alpha = safeAlpha,
                    )
                }
            }
            return RasteredWatermarkCell(
                bitmap = bitmap,
                logicalWidth = logicalWidth,
                logicalHeight = logicalHeight,
                contentWidth = scaledWidth.toFloat(),
                contentHeight = scaledHeight.toFloat(),
            )
        }

        val scaledW = (rawWidth.toDouble() * safeScale.toDouble())
            .coerceAtMost(MAX_LOGICAL_CELL_EDGE_PX.toDouble())
            .toFloat()
        val scaledH = (rawHeight.toDouble() * safeScale.toDouble())
            .coerceAtMost(MAX_LOGICAL_CELL_EDGE_PX.toDouble())
            .toFloat()
        val rotW = WatermarkGeometry.rotatedCellWidth(scaledW, scaledH, degree)
        val rotH = WatermarkGeometry.rotatedCellHeight(scaledW, scaledH, degree)
        val drawWidth = centeredContentBoxDimension(rotW, logicalWidth)
        val drawHeight = centeredContentBoxDimension(rotH, logicalHeight)
        val (rasterWidth, rasterHeight) = rasterDimensionsFor(drawWidth, drawHeight)

        if (rasterWidth == drawWidth && rasterHeight == drawHeight) {
            val scaledIntW = scaledW.toInt().coerceAtLeast(1)
            val scaledIntH = scaledH.toInt().coerceAtLeast(1)
            val scaledImage = scaleIconNearest(icon, scaledIntW, scaledIntH)
            val bitmap = ImageBitmap(drawWidth, drawHeight, ImageBitmapConfig.Argb8888)
            CanvasDrawScope().draw(
                density = Density(1f),
                layoutDirection = LayoutDirection.Ltr,
                canvas = Canvas(bitmap),
                size = Size(drawWidth.toFloat(), drawHeight.toFloat()),
            ) {
                rotate(degrees = degree, pivot = Offset(drawWidth / 2f, drawHeight / 2f)) {
                    drawImage(
                        image = scaledImage,
                        topLeft = Offset(
                            (drawWidth - scaledIntW) / 2f,
                            (drawHeight - scaledIntH) / 2f,
                        ),
                        alpha = safeAlpha,
                    )
                }
            }
            return RasteredWatermarkCell(
                bitmap = bitmap,
                logicalWidth = logicalWidth,
                logicalHeight = logicalHeight,
                drawWidth = drawWidth,
                drawHeight = drawHeight,
                contentWidth = scaledIntW.toFloat(),
                contentHeight = scaledIntH.toFloat(),
            )
        }

        val sx = rasterWidth.toFloat() / drawWidth.toFloat()
        val sy = rasterHeight.toFloat() / drawHeight.toFloat()
        val preScale = safeScale.toDouble() * minOf(sx, sy).toDouble()
        val preW = (rawWidth.toDouble() * preScale).roundToInt().coerceIn(1, MAX_CELL_RASTER_EDGE_PX)
        val preH = (rawHeight.toDouble() * preScale).roundToInt().coerceIn(1, MAX_CELL_RASTER_EDGE_PX)
        val scaledImage = scaleIconNearest(icon, preW, preH)

        val bitmap = ImageBitmap(rasterWidth, rasterHeight, ImageBitmapConfig.Argb8888)
        CanvasDrawScope().draw(
            density = Density(1f),
            layoutDirection = LayoutDirection.Ltr,
            canvas = Canvas(bitmap),
            size = Size(rasterWidth.toFloat(), rasterHeight.toFloat()),
        ) {
            scale(scaleX = sx, scaleY = sy, pivot = Offset.Zero) {
                rotate(degrees = degree, pivot = Offset(drawWidth / 2f, drawHeight / 2f)) {
                    translate(
                        left = (drawWidth - scaledW) / 2f,
                        top = (drawHeight - scaledH) / 2f,
                    ) {
                        scale(
                            scaleX = scaledW / scaledImage.width.toFloat(),
                            scaleY = scaledH / scaledImage.height.toFloat(),
                            pivot = Offset.Zero,
                        ) {
                            drawImage(
                                image = scaledImage,
                                topLeft = Offset.Zero,
                                alpha = safeAlpha,
                            )
                        }
                    }
                }
            }
        }
        return RasteredWatermarkCell(
            bitmap = bitmap,
            logicalWidth = logicalWidth,
            logicalHeight = logicalHeight,
            drawWidth = drawWidth,
            drawHeight = drawHeight,
            contentWidth = scaledW,
            contentHeight = scaledH,
        )
    }

    /**
 * Compose ONE already-rendered watermark [cell] over a [background] [ImageBitmap] — the * platform-neutral commonMain analogue of the Android `WatermarkRenderer.compose` step (which sets a
 * `BitmapShader` on a `Paint` and fills the region). Decode/encode and background acquisition stay a
 * per-platform boundary (Desktop generates/loads + AWT-encodes); this function is pure Compose
 * graphics so Desktop and the eventual iOS surface composite identically.
 *
 * The result is a NEW [ImageBitmap] the size of [background]; the background is drawn first, then:
 * - [WatermarkTileMode.REPEAT]: the cell is **tiled** in a grid from the origin `(0,0)`, stepping by
 * the cell's own width/height across the whole background — the commonMain equivalent of an Android
 * `BitmapShader(REPEAT)` filling the region (the cell already carries its gap padding, so adjacent
 * tiles are gap-spaced). Mirrors `compose`'s else-branch (`translate(0,0)` + fill region) for the
 * REPEAT shader specifically.
 * - [WatermarkTileMode.CLAMP] (the product "decal" mode): ONE cell is drawn at the fractional offset
 * `(offsetX*bgW, offsetY*bgH)` — mirrors `compose`'s CLAMP branch (`translate(offsetX*region,
 * offsetY*region)` + one cell-sized draw).
 *
 * **Only REPEAT and CLAMP are supported.** [WatermarkTileMode.MIRROR] / [WatermarkTileMode.DECAL] are
 * legacy persisted ids that are **not** product-exposed; in the Android renderer the non-CLAMP branch
 * fills the region through a `BitmapShader` whose OWN `Shader.TileMode` governs sampling (mirroring /
 * decal edge behaviour), which a plain origin-stepped draw loop does NOT reproduce. So this primitive
 * does not claim MIRROR/DECAL parity and **throws** for them — adding either needs its own design +
 * golden gate if it ever becomes product-relevant.
 *
 * Cell pixels are drawn with [alpha] (native oracle draws the shader paint with `WaterMark.alpha`;
 * the normalized `0f..1f` equivalent is passed here, default opaque). Tiles are clipped to the
 * background bounds by the surface. Production (ADR-0018): Android uses this via
 * [AndroidCommonRaster]; Desktop/iOS likewise. Native `WatermarkRenderer.compose` remains the
 * dual-path / golden oracle only — no legacy byte-parity claim.
 *
 * @param background the already-decoded destination image
 * @param cell the already-rendered watermark cell (e.g. from [composeTextCell])
 * @param tileMode REPEAT → grid tile; CLAMP → single decal at the fractional offset (others throw)
 * @param offsetX CLAMP horizontal offset as a fraction of background width (0f..1f)
 * @param offsetY CLAMP vertical offset as a fraction of background height (0f..1f)
 * @param alpha normalized watermark opacity 0f..1f (clamped); default 1f
 * @throws IllegalArgumentException for [WatermarkTileMode.MIRROR] / [WatermarkTileMode.DECAL]
     */
    fun composeOverBackground(
        background: ImageBitmap,
        cell: ImageBitmap,
        tileMode: WatermarkTileMode,
        offsetX: Float = 0f,
        offsetY: Float = 0f,
        alpha: Float = 1f,
        tileWidth: Int = cell.width,
        tileHeight: Int = cell.height,
        drawWidth: Int = tileWidth,
        drawHeight: Int = tileHeight,
    ): ImageBitmap {
        // Narrow, explicit contract: REPEAT/CLAMP only. MIRROR/DECAL are not product-exposed and the
        // origin-stepped draw loop here does NOT reproduce their BitmapShader sampling — reject them up
        // front rather than silently aliasing them to REPEAT (no false parity claim).
        val bgWidth = background.width.coerceAtLeast(1)
        val bgHeight = background.height.coerceAtLeast(1)

        val out = ImageBitmap(bgWidth, bgHeight, ImageBitmapConfig.Argb8888)
        CanvasDrawScope().draw(
            density = Density(1f),
            layoutDirection = LayoutDirection.Ltr,
            canvas = Canvas(out),
            size = Size(bgWidth.toFloat(), bgHeight.toFloat()),
        ) {
            drawImage(image = background, topLeft = Offset.Zero)
            drawWatermarkTiles(
                cell = cell,
                tileMode = tileMode,
                destWidth = bgWidth.toFloat(),
                destHeight = bgHeight.toFloat(),
                offsetX = offsetX,
                offsetY = offsetY,
                alpha = alpha,
                tileWidth = tileWidth,
                tileHeight = tileHeight,
                drawWidth = drawWidth,
                drawHeight = drawHeight,
            )
        }
        return out
    }
}

/**
 * Tile [cell] over a destination the size of [destWidth]×[destHeight] (image-pixel space).
 * Same REPEAT / CLAMP / alpha / reject-MIRROR-DECAL contract as
 * [WatermarkCellComposer.composeOverBackground].
 */
fun DrawScope.drawWatermarkTiles(
    cell: ImageBitmap,
    tileMode: WatermarkTileMode,
    destWidth: Float,
    destHeight: Float,
    offsetX: Float = 0f,
    offsetY: Float = 0f,
    alpha: Float = 1f,
    tileWidth: Int = cell.width,
    tileHeight: Int = cell.height,
    drawWidth: Int = tileWidth,
    drawHeight: Int = tileHeight,
) {
    require(tileMode == WatermarkTileMode.REPEAT || tileMode == WatermarkTileMode.CLAMP) {
        "drawWatermarkTiles supports only REPEAT and CLAMP; $tileMode needs a separate design/gate " +
            "(its BitmapShader sampling is not reproduced by this draw loop)"
    }
    val bmpW = cell.width.coerceAtLeast(1)
    val bmpH = cell.height.coerceAtLeast(1)
    val cellWidth = tileWidth.coerceAtLeast(1)
    val cellHeight = tileHeight.coerceAtLeast(1)
    val safeDrawW = drawWidth.coerceIn(1, cellWidth)
    val safeDrawH = drawHeight.coerceIn(1, cellHeight)
    val padX = (cellWidth - safeDrawW) / 2f
    val padY = (cellHeight - safeDrawH) / 2f
    val safeAlpha = alpha.coerceIn(0f, 1f)
    val bgWidth = destWidth.coerceAtLeast(1f)
    val bgHeight = destHeight.coerceAtLeast(1f)
    fun drawTileAt(left: Float, top: Float) {
        val drawLeft = left + padX
        val drawTop = top + padY
        if (safeDrawW == bmpW && safeDrawH == bmpH) {
            drawImage(
                image = cell,
                topLeft = Offset(drawLeft, drawTop),
                alpha = safeAlpha,
            )
        } else {
            translate(left = drawLeft, top = drawTop) {
                scale(
                    scaleX = safeDrawW.toFloat() / bmpW.toFloat(),
                    scaleY = safeDrawH.toFloat() / bmpH.toFloat(),
                    pivot = Offset.Zero,
                ) {
                    drawImage(
                        image = cell,
                        topLeft = Offset.Zero,
                        alpha = safeAlpha,
                    )
                }
            }
        }
    }
    when (tileMode) {
        WatermarkTileMode.CLAMP -> {
            drawTileAt(offsetX * bgWidth, offsetY * bgHeight)
        }
        WatermarkTileMode.REPEAT -> {
            val phaseX = ((offsetX * bgWidth) % cellWidth + cellWidth) % cellWidth
            val phaseY = ((offsetY * bgHeight) % cellHeight + cellHeight) % cellHeight
            var y = if (phaseY == 0f) 0f else phaseY - cellHeight
            while (y < bgHeight) {
                var x = if (phaseX == 0f) 0f else phaseX - cellWidth
                while (x < bgWidth) {
                    drawTileAt(x, y)
                    x += cellWidth
                }
                y += cellHeight
            }
        }
        else -> error("unreachable: $tileMode rejected above")
    }
}
