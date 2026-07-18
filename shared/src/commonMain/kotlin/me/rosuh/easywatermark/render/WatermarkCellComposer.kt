package me.rosuh.easywatermark.render

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.ImageBitmapConfig
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import me.rosuh.easywatermark.data.model.WatermarkTileMode

/**
 * S4d-2: the first real piece of the commonMain watermark renderer (CMP plan C2 / ADR-0004): an
 * **offscreen cell composition primitive** built on multiplatform Compose graphics, sized by the
 * shared [WatermarkGeometry] core. Compiles and runs on Android + desktop(JVM) + iOS.
 *
 * This composes ONE watermark "cell" into an offscreen [ImageBitmap], mirroring the historical
 * Android native cell pipeline (`WatermarkRenderer.buildTextShader`/`buildIconShader` — now a
 * measurement/golden oracle under ADR-0018; production uses this common path via platform adapters):
 *
 *  - cell size = rotated-AABB of the content box (`WatermarkGeometry.rotatedCellWidth/Height`)
 *    expanded by the gap percents (`horizontalGap`/`verticalGap`) - the SAME math the Android
 *    renderer uses;
 *  - draw onto an offscreen surface, rotate about the cell centre, draw the content centred.
 *
 * SCOPE (deliberately narrow, S4d-2 / S4d-3):
 *  - [composeRotatedCell] (S4d-2) is the **offscreen -> draw -> rotate -> ImageBitmap** scaffold
 *    only. Its content is a placeholder rect at the content bounds - it is NOT the text/icon
 *    raster.
 *  - [composeTextCell] (S4d-3) is the **text raster** follow-up: it measures + paints a real text
 *    cell offscreen using a platform-injected [TextRasterEnv] (the bootstrap ADR-0004 calls out).
 *    It is the commonMain analogue of the Android `WatermarkRenderer.buildTextShader` text path
 *    (measure via `TextMeasurer`, draw via `Paragraph.paint` instead of legacy `StaticLayout`).
 *  - [composeIconCell] (S4d-4) is the **icon/image raster** follow-up: it scales/rotates/centres an
 *    ALREADY-DECODED Compose [ImageBitmap] into the offscreen cell, the commonMain analogue of the
 *    Android `WatermarkRenderer.buildIconShader`. Image DECODE (from `Uri`/`ContentResolver`/bytes/
 *    EXIF/downsample) stays a platform boundary and is NOT in commonMain (see the S4d-4
 *    `decode-boundary.md`).
 *  - **Production (ADR-0018):** Android preview/export route through [AndroidCommonRaster] →
 *    these primitives + [composeOverBackground]; Desktop/iOS use the same common path. Native
 *    `WatermarkRenderer` remains dual-path / golden oracle only — **no legacy byte-parity claim**.
 *  - Tiling/REPEAT/CLAMP over a photo is [composeOverBackground] (common) or native
 *    `WatermarkRenderer.compose` (oracle).
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

    /**
     * S4d-3: compose ONE rotated, gap-spaced watermark **text** cell into an offscreen [ImageBitmap]
     * — the commonMain text-raster analogue of the Android `WatermarkRenderer.buildTextShader` text
     * path. The platform font bootstrap is the injected [env] (see [TextRasterEnv]); this function
     * never constructs a `FontFamily.Resolver` itself, so no Android `Context` / Skiko default leaks
     * into commonMain.
     *
     * Pipeline (mirrors the Android text cell 1:1):
     *  1. **Measure** [content] with `TextMeasurer(env.fontFamilyResolver, env.density,
     *     env.layoutDirection).measure(...)` — the SAME measurement path the Android
     *     `WatermarkTextMeasurer` uses (S3b), so the cell box is computed identically.
     *  2. **Size** the cell via the shared [WatermarkGeometry] (rotated-AABB + gap), identical math
     *     to `WatermarkRenderer.buildTextShader`.
     *  3. **Draw** onto an offscreen [ImageBitmap] via [CanvasDrawScope], rotating about the cell
     *     centre, then **raster** the text with `layoutResult.multiParagraph.paint(Canvas(bitmap),
     *     color)` — the commonMain equivalent of `StaticLayout.draw(canvas)`.
     *
     * Placement: the text box is translated to **`(finalWidth - textWidth)/2` horizontally and
     * `(finalHeight - textHeight)/2` vertically** to centre the measured box inside the cell, and for
     * **multiline** text (`lineCount > 1`) the measured style uses **`TextAlign.Center`** (S4d-12) so
     * **each line is centred within that box** — reproducing the Android `buildTextShader` placement,
     * which draws a CENTER-aligned `TextPaint` `StaticLayout` translated to `canvas.translate(finalW/2,
     * …)`. This fixes the S4d-11 horizontal divergence (shorter multiline rows previously left-aligned
     * at the box edge instead of centred: `multiline` band-centre 43 → 80 on device). Single-line text
     * keeps the default alignment — Center is logically a no-op there but perturbs a single ROTATED
     * line sub-pixel (the rotated emoji default regressed under blanket Center), and single-line must
     * stay byte-identical (S4d-10). The vertical offset stays full-box centred and is NOT changed to
     * Android's line-0-based offset/clip (root cause #2, deferred to an owner decision — S4d-11/S4d-12).
     *
     * Production (ADR-0018): used on Android (via [AndroidCommonRaster]) and Desktop/iOS. Cross-
     * platform / native-oracle pixel parity vs `StaticLayout` is **not** asserted (CJK/engine delta
     * expected). Unit coverage: `WatermarkCellComposerTest` on `:shared:desktopTest`.
     *
     * @param env            the platform-injected text bootstrap (resolver + density + layout dir)
     * @param content        the text content to measure + paint (text + TextStyle + fill colour)
     * @param degree         rotation in degrees (matches `WaterMark.degree`)
     * @param hGapPercent    horizontal gap percent (0 -> adjacent, 100 -> 2x); matches `WaterMark.hGap`
     * @param vGapPercent    vertical gap percent; matches `WaterMark.vGap`
     * @param backgroundColor cell background (transparent by default, like the production cell)
     */
    fun composeTextCell(
        env: TextRasterEnv,
        content: WatermarkTextContent,
        degree: Float,
        hGapPercent: Int = 0,
        vGapPercent: Int = 0,
        backgroundColor: Color = Color.Transparent,
    ): ImageBitmap {
        require(content.text.isNotEmpty()) { "composeTextCell requires non-empty text" }

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
        // S4d-12 (root cause #1, S4d-11): center EACH line within the measured paragraph box for
        // MULTILINE text, matching the Android renderer's CENTER `TextPaint` (`PainKtx.applyConfig`) +
        // `StaticLayout` behaviour. Under unbounded measurement the paragraph width == the widest line,
        // so `TextAlign.Center` centers shorter lines within that box (e.g. "DO NOT" over "REDISTRIBUTE").
        // Applied ONLY when `lineCount > 1`: for a single line Center is logically a no-op, but it is NOT
        // pixel-free in practice — it perturbs sub-pixel placement of a single rotated line (measured on
        // device: the rotated emoji default regressed under blanket Center), and S4d-10 requires
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
        val finalWidth = WatermarkGeometry.horizontalGap(fixWidth.toInt(), hGapPercent).coerceAtLeast(1)
        val finalHeight = WatermarkGeometry.verticalGap(fixHeight.toInt(), vGapPercent).coerceAtLeast(1)

        // 3) Offscreen raster: rotate about the cell centre, then paint the text centred.
        val bitmap = ImageBitmap(finalWidth, finalHeight, ImageBitmapConfig.Argb8888)
        CanvasDrawScope().draw(
            density = env.density,
            layoutDirection = env.layoutDirection,
            canvas = Canvas(bitmap),
            size = Size(finalWidth.toFloat(), finalHeight.toFloat()),
        ) {
            if (backgroundColor != Color.Transparent) {
                drawRect(color = backgroundColor)
            }
            // Mirrors `canvas.rotate(degree, finalW/2, finalH/2)` then
            // `canvas.translate(finalW/2, (finalH - lineHeight) / 2)` from the Android renderer
            // (whose TextPaint is CENTER-aligned). Here we rotate about the cell centre, then
            // centre the measured TEXT BOX inside the cell by translating its top-left to
            // ((finalWidth - textWidth)/2, (finalHeight - textHeight)/2). MultiParagraph.paint draws
            // from the canvas origin (top-left of the text box), so this box-centring — NOT a
            // translate to finalWidth/2 — is what places the text correctly; translating to
            // finalWidth/2 would paint the paragraph starting at the cell centre and clip the right
            // half for degree=0/no-gap (where finalWidth == textWidth). Using the DrawScope transform
            // stack (rotate -> translate -> drawIntoCanvas) keeps the platform canvas balanced,
            // instead of hand-managing canvas.save/restore.
            rotate(degrees = degree, pivot = Offset(finalWidth / 2f, finalHeight / 2f)) {
                translate(
                    left = (finalWidth - textWidth) / 2f,
                    top = (finalHeight - textHeight) / 2f,
                ) {
                    // drawIntoCanvas reaches the underlying commonMain Canvas; multiParagraph.paint
                    // paints the laid-out text from the (now box-centred) canvas origin. The layout was
                    // measured with TextAlign.Center (S4d-12), so each line is also centred WITHIN the
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
        return bitmap
    }

    /** Android `buildIconShader` derives the icon scale from `config.textSize / 14f` (14 ⇒ 1×). */
    const val ICON_SCALE_REFERENCE_TEXT_SIZE: Float = 14f

    /**
     * S4d-4: compose ONE rotated, gap-spaced watermark **icon/image** cell into an offscreen
     * [ImageBitmap] — the commonMain analogue of the Android `WatermarkRenderer.buildIconShader`.
     *
     * Takes an **already-decoded** Compose [icon] [ImageBitmap]; **decode is NOT done here**
     * (`Uri`/`ContentResolver`/file bytes/EXIF/downsample stay a per-platform boundary — see the
     * S4d-4 `decode-boundary.md`), so no `android.graphics.*` leaks into commonMain.
     *
     * Pipeline (mirrors the Android icon cell 1:1, `WatermarkRenderer.kt:187-234`):
     *  1. raw dims = `icon.width`/`height`, each coerced to ≥ 1;
     *  2. base cell is a **square** of side `WatermarkGeometry.diagonal(rawHeight, rawWidth)`
     *     (`diagonal` is symmetric; the arg order mirrors the Android call), expanded by the gap
     *     percents — identical to `buildIconShader`;
     *  3. apply [scaleRatio] (production: `textSize / 14f`, see [ICON_SCALE_REFERENCE_TEXT_SIZE]) to
     *     both the cell and the drawn image;
     *  4. rotate about the cell centre and draw the image **scaled to `rawW*scale × rawH*scale`,
     *     centred** in the cell.
     *
     * Placement (S4d-7): pivot and centring follow Android's **float** placement *math* — the
     * un-truncated cell extent `finalWidth * scaleRatio` — rotating about `Offset(cellW/2, cellH/2)`
     * and placing the scaled image at the float top-left `((cellW - scaledWidth)/2,
     * (cellH - scaledHeight)/2)` (vs the earlier integer-truncated pivot/offset). This matches the
     * *placement geometry* of `canvas.rotate(degree, finalW*scaleRatio/2, …); drawBitmap(scaled,
     * (finalW*scaleRatio - scaled.width)/2f, …)`. The cell is still ALLOCATED at the integer
     * `(finalW*scaleRatio).toInt()` (a bitmap is integer-sized, as is Android's `targetBitmap`).
     *
     * **This does NOT make the commonMain raster byte-identical to Android for rotated non-uniform
     * icons.** Float placement is *more correct* and reduced the S4d-6 strict-golden delta on
     * `icon_rot_315` (16 → 5 / 1936 px), but a residual remains: the rotated point-draw used here
     * (`drawImage(topLeft)`) has no `filterQuality` parameter, so the rotation resamples at the
     * DrawScope default (not guaranteed nearest), whereas Android draws with a bare nearest paint. No
     * commonMain `drawImage` overload offers float placement AND nearest filtering together (the
     * `dstSize` overload is nearest but rect-maps with a half-texel offset; only the platform
     * `nativeCanvas.drawBitmap` has both). Production icons still go through this common path on
     * Android (ADR-0018); residual vs native oracle is accepted — **not** byte-identical for
     * rotated non-uniform icons (historical S4d-6→S4d-8 measurement).
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
     * @param icon         the already-decoded source image
     * @param degree       rotation in degrees (matches `WaterMark.degree`)
     * @param hGapPercent  horizontal gap percent (0 → adjacent, 100 → 2×); matches `WaterMark.hGap`
     * @param vGapPercent  vertical gap percent; matches `WaterMark.vGap`
     * @param scaleRatio   icon scale (production passes `textSize / ICON_SCALE_REFERENCE_TEXT_SIZE`)
     * @param alpha        normalized watermark opacity in `0f..1f` (clamped); the Android adapter
     *                     passes `WaterMark.alpha / 255f`. Default `1f` is a bootstrap convenience
     *                     that matches `WaterMark.alpha = 255`; production wiring must pass the
     *                     configured value, e.g. `128 / 255f`.
     */
    fun composeIconCell(
        icon: ImageBitmap,
        degree: Float,
        hGapPercent: Int = 0,
        vGapPercent: Int = 0,
        scaleRatio: Float = 1f,
        alpha: Float = 1f,
    ): ImageBitmap {
        val rawWidth = icon.width.coerceAtLeast(1).toFloat()
        val rawHeight = icon.height.coerceAtLeast(1).toFloat()
        val safeScale = if (scaleRatio > 0f) scaleRatio else 1f
        val safeAlpha = alpha.coerceIn(0f, 1f)

        // SAME math as WatermarkRenderer.buildIconShader: square base cell sized by the content
        // diagonal (arg order mirrors Android; `diagonal` is symmetric), then gap expansion.
        val maxSize = WatermarkGeometry.diagonal(rawHeight, rawWidth)
        val finalWidth = WatermarkGeometry.horizontalGap(maxSize, hGapPercent)
        val finalHeight = WatermarkGeometry.verticalGap(maxSize, vGapPercent)

        // Float cell extent (un-truncated) drives pivot + placement, following Android's
        // `finalWidth * scaleRatio` placement math. The bitmap is allocated at the integer truncation
        // of it (a bitmap is integer-sized — Android's `targetBitmap` is `(finalWidth*scaleRatio).toInt()` too).
        val cellWidth = finalWidth * safeScale
        val cellHeight = finalHeight * safeScale
        val targetWidth = cellWidth.toInt().coerceAtLeast(1)
        val targetHeight = cellHeight.toInt().coerceAtLeast(1)
        val scaledWidth = (rawWidth * safeScale).toInt().coerceAtLeast(1)
        val scaledHeight = (rawHeight * safeScale).toInt().coerceAtLeast(1)

        // Follow Android's TWO-STEP shape: (1) pre-scale the source to scaledWidth×scaledHeight with
        // nearest sampling (the `Bitmap.createScaledBitmap(..., filter=false)` analogue), then (2) draw
        // that pre-scaled image 1:1 at a FLOAT top-left under the FLOAT-pivot rotation (the
        // `canvas.drawBitmap(scaled, x, y, paint)` analogue — a point draw, NOT a src→dst rect map).
        // The earlier dst-rect draw added a half-texel sampling offset that shifted interior boundary
        // pixels of non-uniform rotated icons (S4d-6 `icon_rot_315`, 16/1936 px); the point draw
        // REDUCES that (to ~5/1936) by fixing placement, but does NOT eliminate it — the point-draw
        // overload has no `filterQuality`, so the rotation is not guaranteed nearest like Android's
        // bare-paint `drawBitmap`. commonMain has no float-placement + nearest-filter overload, so this
        // is not byte-identical to the native Android oracle (accepted under ADR-0018).
        val scaledImage: ImageBitmap =
            if (scaledWidth == icon.width && scaledHeight == icon.height) {
                icon
            } else {
                val tmp = ImageBitmap(scaledWidth, scaledHeight, ImageBitmapConfig.Argb8888)
                CanvasDrawScope().draw(
                    density = Density(1f),
                    layoutDirection = LayoutDirection.Ltr,
                    canvas = Canvas(tmp),
                    size = Size(scaledWidth.toFloat(), scaledHeight.toFloat()),
                ) {
                    drawImage(
                        image = icon,
                        srcOffset = IntOffset.Zero,
                        srcSize = IntSize(icon.width, icon.height),
                        dstOffset = IntOffset.Zero,
                        dstSize = IntSize(scaledWidth, scaledHeight),
                        filterQuality = FilterQuality.None,
                    )
                }
                tmp
            }

        val bitmap = ImageBitmap(targetWidth, targetHeight, ImageBitmapConfig.Argb8888)
        CanvasDrawScope().draw(
            density = Density(1f),
            layoutDirection = LayoutDirection.Ltr,
            canvas = Canvas(bitmap),
            size = Size(targetWidth.toFloat(), targetHeight.toFloat()),
        ) {
            // Follows Android's placement math `canvas.rotate(degree, finalW*scaleRatio/2,
            // finalH*scaleRatio/2)` then `drawBitmap(scaled, (finalW*scaleRatio - scaled.w)/2f, …)`:
            // rotate about the FLOAT cell centre, then draw the pre-scaled image at the FLOAT top-left
            // (point draw — the drawImage(topLeft) overload, the drawBitmap(bmp,x,y) analogue). NOTE:
            // this overload has no filterQuality, so the rotation sampling is not guaranteed nearest —
            // hence not byte-identical to Android's bare-paint drawBitmap for rotated non-uniform icons.
            rotate(degrees = degree, pivot = Offset(cellWidth / 2f, cellHeight / 2f)) {
                drawImage(
                    image = scaledImage,
                    topLeft = Offset(
                        (cellWidth - scaledWidth) / 2f,
                        (cellHeight - scaledHeight) / 2f,
                    ),
                    // Watermark opacity: Android draws with Paint.alpha = WaterMark.alpha (0..255);
                    // the normalized equivalent feeds drawImage's alpha.
                    alpha = safeAlpha,
                )
            }
        }
        return bitmap
    }

    /**
     * S4d-19: compose ONE already-rendered watermark [cell] over a [background] [ImageBitmap] — the
     * platform-neutral commonMain analogue of the Android `WatermarkRenderer.compose` step (which sets a
     * `BitmapShader` on a `Paint` and fills the region). Decode/encode and background acquisition stay a
     * per-platform boundary (Desktop generates/loads + AWT-encodes); this function is pure Compose
     * graphics so Desktop and the eventual iOS surface composite identically.
     *
     * The result is a NEW [ImageBitmap] the size of [background]; the background is drawn first, then:
     *  - [WatermarkTileMode.REPEAT]: the cell is **tiled** in a grid from the origin `(0,0)`, stepping by
     *    the cell's own width/height across the whole background — the commonMain equivalent of an Android
     *    `BitmapShader(REPEAT)` filling the region (the cell already carries its gap padding, so adjacent
     *    tiles are gap-spaced). Mirrors `compose`'s else-branch (`translate(0,0)` + fill region) for the
     *    REPEAT shader specifically.
     *  - [WatermarkTileMode.CLAMP] (the product "decal" mode): ONE cell is drawn at the fractional offset
     *    `(offsetX*bgW, offsetY*bgH)` — mirrors `compose`'s CLAMP branch (`translate(offsetX*region,
     *    offsetY*region)` + one cell-sized draw).
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
     * @param cell       the already-rendered watermark cell (e.g. from [composeTextCell])
     * @param tileMode   REPEAT → grid tile; CLAMP → single decal at the fractional offset (others throw)
     * @param offsetX    CLAMP horizontal offset as a fraction of background width (0f..1f)
     * @param offsetY    CLAMP vertical offset as a fraction of background height (0f..1f)
     * @param alpha      normalized watermark opacity 0f..1f (clamped); default 1f
     * @throws IllegalArgumentException for [WatermarkTileMode.MIRROR] / [WatermarkTileMode.DECAL]
     */
    fun composeOverBackground(
        background: ImageBitmap,
        cell: ImageBitmap,
        tileMode: WatermarkTileMode,
        offsetX: Float = 0f,
        offsetY: Float = 0f,
        alpha: Float = 1f,
    ): ImageBitmap {
        // Narrow, explicit contract: REPEAT/CLAMP only. MIRROR/DECAL are not product-exposed and the
        // origin-stepped draw loop here does NOT reproduce their BitmapShader sampling — reject them up
        // front rather than silently aliasing them to REPEAT (no false parity claim).
        require(tileMode == WatermarkTileMode.REPEAT || tileMode == WatermarkTileMode.CLAMP) {
            "composeOverBackground supports only REPEAT and CLAMP; $tileMode needs a separate design/gate " +
                "(its BitmapShader sampling is not reproduced by this draw loop)"
        }
        val bgWidth = background.width.coerceAtLeast(1)
        val bgHeight = background.height.coerceAtLeast(1)
        val cellWidth = cell.width.coerceAtLeast(1)
        val cellHeight = cell.height.coerceAtLeast(1)
        val safeAlpha = alpha.coerceIn(0f, 1f)

        val out = ImageBitmap(bgWidth, bgHeight, ImageBitmapConfig.Argb8888)
        CanvasDrawScope().draw(
            density = Density(1f),
            layoutDirection = LayoutDirection.Ltr,
            canvas = Canvas(out),
            size = Size(bgWidth.toFloat(), bgHeight.toFloat()),
        ) {
            // 1) Background first (full opacity).
            drawImage(image = background, topLeft = Offset.Zero)

            // 2) Watermark cell(s).
            when (tileMode) {
                WatermarkTileMode.CLAMP -> {
                    // Single decal at the fractional offset (the product "decal" mode).
                    drawImage(
                        image = cell,
                        topLeft = Offset(offsetX * bgWidth, offsetY * bgHeight),
                        alpha = safeAlpha,
                    )
                }
                WatermarkTileMode.REPEAT -> {
                    // Tile from the origin across the whole background, stepping by the cell's own dims
                    // (the cell already includes its gap padding).
                    var y = 0
                    while (y < bgHeight) {
                        var x = 0
                        while (x < bgWidth) {
                            drawImage(image = cell, topLeft = Offset(x.toFloat(), y.toFloat()), alpha = safeAlpha)
                            x += cellWidth
                        }
                        y += cellHeight
                    }
                }
            }
        }
        return out
    }
}
