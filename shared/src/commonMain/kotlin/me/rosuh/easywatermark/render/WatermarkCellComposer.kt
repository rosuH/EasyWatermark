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
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
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
 *  - **Not wired into production.** Android preview (`EditorScreen.WaterMarkCanvas`) and export
 *    (`MainViewModel.generateImage`) still use the Android-only `WatermarkRenderer` seam, so the
 *    strict renderer goldens and on-device behaviour are unchanged by this slice. These primitives
 *    are verified independently by `WatermarkCellComposerTest` (commonTest / `:shared:desktopTest`).
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
     * Placement: `MultiParagraph.paint` draws from the current canvas origin (top-left of the text
     * box), so the text box is translated to **`(finalWidth - textWidth)/2` horizontally and
     * `(finalHeight - textHeight)/2` vertically** to centre the measured text box inside the cell
     * — the same box-centring the Android `buildTextShader` achieves via `canvas.translate(finalW/2,
     * (finalH - lineHeight)/2)` on a CENTER-aligned `TextPaint`. This API does NOT force a centered
     * [WatermarkTextContent.style]; callers pass a normal (typically left-aligned) `TextStyle` and
     * the box-centring handles placement, so the text is never painted starting at the cell centre
     * and clipped (which would happen for `degree=0` / no gap, where `finalWidth == textWidth`).
     *
     * SCOPE (S4d-3): **not wired into production**. The Android preview/export renderer is
     * unchanged; this is verified test-scope-only (`WatermarkCellComposerTest` on
     * `:shared:desktopTest`). Cross-platform pixel parity vs the Android `StaticLayout` raster is
     * NOT asserted here — see `artifacts/parity-gate-plan.md` in the S4d-3 session for what must be
     * proven before production wiring.
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
        val layout = measurer.measure(
            text = AnnotatedString(content.text),
            style = content.style,
        )
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
                    // paints the laid-out text from the (now box-centred) canvas origin.
                    drawIntoCanvas { canvas ->
                        layout.multiParagraph.paint(canvas, content.color)
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
     * Scaling: Android pre-scales the source with `Bitmap.createScaledBitmap(..., filter=false)`
     * (nearest-neighbor). The commonMain equivalent is `DrawScope.drawImage(dstSize = …,
     * filterQuality = FilterQuality.None)` — nearest-neighbor (ADR-0014 "icon filter=false" parity).
     * NOTE: `FilterQuality.None` ≈ nearest-neighbor but is NOT guaranteed pixel-identical to Android
     * `createScaledBitmap`; cross-platform/cross-impl pixel parity is a parity-gate concern, not
     * asserted by this bootstrap (see `parity-gate-plan.md`).
     *
     * Opacity: the Android icon path draws the scaled bitmap with a `Paint` whose
     * `alpha = WaterMark.alpha` (0..255, see `PainKtx.applyConfig`), so image/icon watermarks honour
     * the watermark opacity. The commonMain analogue takes a normalized [alpha] in `0f..1f` (clamped
     * at the raster boundary) and passes it to `drawImage(alpha = …)`. The future Android adapter
     * must pass `WaterMark.alpha / 255f` to reach parity (`alpha = 128` ⇒ `0.5f`). The default `1f`
     * is a bootstrap convenience that matches the current default `WaterMark.alpha = 255`; future
     * production wiring must still pass the actual configured alpha, not rely on this default.
     *
     * Bootstrap safety guard: Android allocates `(finalWidth*scaleRatio).toInt()` with no explicit
     * min and would crash on a 0-size bitmap; this commonMain bootstrap coerces target/scaled dims to
     * ≥ 1 (documented delta — it only affects degenerate `scaleRatio→0` / 0-size inputs).
     *
     * SCOPE (S4d-4): **not wired into production**. Android preview/export still use
     * `WatermarkRenderer.buildIconShader`; this is verified test-scope-only on `:shared:desktopTest`
     * (`WatermarkIconCellRasterTest`). Recycled-bitmap rejection (Android's `srcBitmap.isRecycled`)
     * is not observable in commonMain — input validity is the caller/decode-boundary responsibility.
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

        val targetWidth = (finalWidth * safeScale).toInt().coerceAtLeast(1)
        val targetHeight = (finalHeight * safeScale).toInt().coerceAtLeast(1)
        val scaledWidth = (rawWidth * safeScale).toInt().coerceAtLeast(1)
        val scaledHeight = (rawHeight * safeScale).toInt().coerceAtLeast(1)

        val bitmap = ImageBitmap(targetWidth, targetHeight, ImageBitmapConfig.Argb8888)
        CanvasDrawScope().draw(
            density = Density(1f),
            layoutDirection = LayoutDirection.Ltr,
            canvas = Canvas(bitmap),
            size = Size(targetWidth.toFloat(), targetHeight.toFloat()),
        ) {
            // Mirrors `canvas.rotate(degree, targetW/2, targetH/2)` then the centred `drawBitmap`.
            rotate(degrees = degree, pivot = Offset(targetWidth / 2f, targetHeight / 2f)) {
                drawImage(
                    image = icon,
                    srcOffset = IntOffset.Zero,
                    srcSize = IntSize(icon.width, icon.height),
                    // Centre the scaled image in the cell (integer placement; Android uses a float
                    // top-left — sub-pixel delta is a parity-gate concern, not asserted here).
                    dstOffset = IntOffset(
                        (targetWidth - scaledWidth) / 2,
                        (targetHeight - scaledHeight) / 2,
                    ),
                    dstSize = IntSize(scaledWidth, scaledHeight),
                    // Watermark opacity: Android draws with Paint.alpha = WaterMark.alpha (0..255);
                    // the normalized equivalent feeds drawImage's alpha. FilterQuality.None preserved.
                    alpha = safeAlpha,
                    filterQuality = FilterQuality.None,
                )
            }
        }
        return bitmap
    }
}
