package me.rosuh.easywatermark.render

import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Shader
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import androidx.core.graphics.withSave
import kotlinx.coroutines.withContext
import me.rosuh.easywatermark.data.model.ImageInfo
import me.rosuh.easywatermark.data.model.WaterMark
import me.rosuh.easywatermark.ui.widget.utils.WaterMarkShader
import kotlin.coroutines.CoroutineContext
import kotlin.math.max

/**
 * Android-only watermark renderer seam (CMP plan S2a). Centralizes the CURRENT preview/export
 * watermark logic that was duplicated across [me.rosuh.easywatermark.ui.widget.WaterMarkImageView]
 * (preview `onDraw` + companion cell builders) and
 * [me.rosuh.easywatermark.ui.MainViewModel] `generateImage` (export):
 *
 *  - [buildTextShader] / [buildIconShader] — build one watermark cell + its tiling [BitmapShader]
 *    (legacy Android `StaticLayout` / scaled-bitmap path; cell sizing via commonMain
 *    [WatermarkGeometry]).
 *  - [compose] — draw the cell shader over a target canvas: REPEAT tiles a region, CLAMP paints one
 *    decal at a fractional offset. Preview and export now call the SAME helper.
 *
 * This is an EXTRACTION-ONLY slice: the bodies below are moved verbatim from the old call sites, so
 * rendered pixels are unchanged (guarded by the S0 strict export golden + the S2a composition
 * equivalence test). It is deliberately **Android-only** (it touches `android.graphics.*`,
 * `android.text.StaticLayout`, `TextPaint`) and therefore lives in `:app`, NOT `shared/commonMain` —
 * the platform-neutral renderer is a later, explicitly-approved migration slice. Likewise it keeps
 * the legacy text path and does NOT adopt `TextMeasurer`/`TextMeasureEnv` in this slice.
 */
object WatermarkRenderer {

    /**
     * S3a image-space sizing reference width (px). `textSize` is interpreted as image-space:
     *
     * ```
     * textPx = textSize * imageWidth / REF_WIDTH
     * ```
     *
     * where `imageWidth` is the width of the bitmap the watermark cell tiles over (the displayed
     * drawable in preview, the full source image at export — both already carried by
     * `ImageInfo.width`). This makes the watermark a constant fraction (`textSize / REF_WIDTH`) of the
     * image on every device and in both preview and export, replacing the old, device-dependent,
     * un-persisted preview-matrix scale (`1/MSCALE_X`).
     *
     * **Why 1000:** it is the canonical reference image width already used by every existing
     * golden/gate in this repo (`WatermarkExportGoldenTest`, `WatermarkCellGoldenTest`,
     * `WatermarkCellInstrumentedGoldenTest`, `WatermarkCellParityGateTest` all use
     * `ImageInfo.width = 1000`). At that reference width the formula reproduces the legacy unscaled
     * paint size exactly (`textSize * 1000 / 1000 == textSize`), so `textSize` keeps its historical
     * meaning at the reference and scales proportionally elsewhere. 1000 is also representative of a
     * typical editor preview-canvas width on the dominant ~1080px-wide phone class (canvas = screen −
     * padding ≈ 1000), so the typical user's export size is approximately preserved — the bounded
     * one-time shift accepted under D3 Option A (see ACSP ref-width-decision.md). A precise
     * production-preview-width recalibration on the authority device is a later optional refinement.
     */
    const val REF_WIDTH: Float = 1000f

    /**
     * Build the text watermark cell + REPEAT/CLAMP [BitmapShader].
     *
     * S3b (D1 accepted): the cell BOX is measured via the platform-neutral
     * [WatermarkTextMeasurer]/[TextMeasureEnv] seam — width is byte-exact vs legacy `StaticLayout`
     * (device-independent), CJK height follows the Compose line-height (signed device baseline,
     * `WatermarkCellParityGateTest`), non-CJK height is unchanged. DRAWING still uses legacy
     * `StaticLayout` + the supplied [textPaint] (configured via `TextPaint.applyConfig`) — only
     * measurement moved to the seam, not the rasterization. The [env] is the injected measurement
     * environment (Android bootstrap via `androidTextMeasureEnv(context)` at the call sites).
     */
    suspend fun buildTextShader(
        imageInfo: ImageInfo,
        config: WaterMark,
        textPaint: TextPaint,
        env: TextMeasureEnv,
        coroutineContext: CoroutineContext,
    ): WaterMarkShader? = withContext(coroutineContext) {
        if (config.text.isBlank()) {
            return@withContext null
        }
        val showDebugRect = config.enableBounds
        val tileMode = config.obtainTileMode()

        // S3b: measure the cell box with the C2b seam (width == legacy StaticLayout.width exactly;
        // CJK height per Compose line-height; non-CJK == legacy).
        val measured = WatermarkTextMeasurer.measure(env, config.text, textPaint.toWatermarkTextStyle())

        // Legacy StaticLayout retained FOR DRAWING ONLY (S3b is measurement-only; rasterization unchanged).
        var maxLineWidth = 0
        config.text.split("\n").forEach {
            val startIndex = config.text.indexOf(it).coerceAtLeast(0)
            val lineWidth = textPaint.measureText(
                config.text,
                startIndex,
                (startIndex + it.length).coerceAtMost(config.text.length)
            ).toInt()
            maxLineWidth = max(maxLineWidth, lineWidth)
        }

        val staticLayout =
            StaticLayout.Builder.obtain(
                config.text,
                0,
                config.text.length,
                textPaint,
                maxLineWidth
            )
                .setAlignment(Layout.Alignment.ALIGN_NORMAL)
                .build()

        val textWidth = measured.width.toFloat().coerceAtLeast(1f)
        val textHeight = measured.height.toFloat().coerceAtLeast(1f)

        // C2a: delegate cell sizing to the shared commonMain engine core (behavior-identical
        // formulas; pinned by WatermarkCellGoldenTest). Verified rendering parity on-device.
        val fixWidth = WatermarkGeometry.rotatedCellWidth(textWidth, textHeight, config.degree)
        val fixHeight = WatermarkGeometry.rotatedCellHeight(textWidth, textHeight, config.degree)
        val finalWidth = WatermarkGeometry.horizontalGap(fixWidth.toInt(), config.hGap)
        val finalHeight = WatermarkGeometry.verticalGap(fixHeight.toInt(), config.vGap)
        val bitmap = Bitmap.createBitmap(finalWidth, finalHeight, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        if (showDebugRect) {
            val tmpPaint = Paint().apply {
                color = Color.RED
                strokeWidth = 1f
                style = Paint.Style.STROKE
            }
            canvas.drawRect(0f, 0f, finalWidth.toFloat(), finalHeight.toFloat(), tmpPaint)
            canvas.save()
        }
        // rotate by user input
        canvas.rotate(
            config.degree,
            (finalWidth / 2).toFloat(),
            (finalHeight / 2).toFloat()
        )
        // draw text
        canvas.withSave {
            this.translate(
                ((finalWidth) / 2).toFloat(),
                ((finalHeight - staticLayout.getLineBottom(0) - staticLayout.getLineTop(0)) / 2).toFloat()
            )
            staticLayout.draw(canvas)
        }

        if (showDebugRect) {
            canvas.restore()
        }
        val bitmapShader = BitmapShader(
            bitmap,
            tileMode,
            tileMode
        )
        return@withContext WaterMarkShader(
            bitmapShader,
            bitmap.width,
            bitmap.height
        )
    }

    /**
     * Build the icon watermark cell + REPEAT/CLAMP [BitmapShader]. Verbatim extraction of the former
     * `WaterMarkImageView.buildIconBitmapShader` (which now delegates here). Preserves the legacy
     * nearest-neighbor `Bitmap.createScaledBitmap(..., false)` behavior.
     */
    suspend fun buildIconShader(
        @Suppress("UNUSED_PARAMETER") imageInfo: ImageInfo, // S3a: icon no longer reads scaleX; kept for API compatibility
        srcBitmap: Bitmap,
        config: WaterMark,
        textPaint: Paint,
        @Suppress("UNUSED_PARAMETER") scale: Boolean, // S3a: view-scale coupling removed; param kept for source/API compatibility
        coroutineContext: CoroutineContext,
    ): WaterMarkShader? = withContext(coroutineContext) {
        if (srcBitmap.isRecycled) {
            return@withContext null
        }
        val tileMode = config.obtainTileMode()
        val showDebugRect = config.enableBounds
        val rawWidth = srcBitmap.width.toFloat().coerceAtLeast(1f)
        val rawHeight = srcBitmap.height.toFloat().coerceAtLeast(1f)

        // C2a: icon-cell sizing via the shared commonMain engine core (equivalence pinned by
        // WatermarkCellGoldenTest.iconCell_dimensions_match_geometry).
        val maxSize = WatermarkGeometry.diagonal(rawHeight, rawWidth)
        val finalWidth = WatermarkGeometry.horizontalGap(maxSize, config.hGap)
        val finalHeight = WatermarkGeometry.verticalGap(maxSize, config.vGap)
        // S3a: `textSize` is the icon scale ratio (textSize/14 ⇒ 14 = 1×), preserved from legacy.
        // The old preview-matrix `imageInfo.scaleX` factor (applied only at export, `scale=true`) is
        // REMOVED so preview and export size icons identically and independent of view scale (D2c).
        val scaleRatio = config.textSize / 14f

        val targetBitmap = Bitmap.createBitmap(
            (finalWidth * scaleRatio).toInt(),
            (finalHeight * scaleRatio).toInt(),
            Bitmap.Config.ARGB_8888
        )

        val canvas = Canvas(targetBitmap)

        val scaleBitmap = Bitmap.createScaledBitmap(
            srcBitmap,
            (rawWidth * scaleRatio).toInt(), (rawHeight * scaleRatio).toInt(),
            false
        )

        if (showDebugRect) {
            val tmpPaint = Paint().apply {
                color = Color.RED
                strokeWidth = 1f
                style = Paint.Style.STROKE
            }
            canvas.drawRect(0f, 0f, finalWidth * scaleRatio, finalHeight * scaleRatio, tmpPaint)
            canvas.save()
        }
        canvas.rotate(
            config.degree,
            (finalWidth * scaleRatio / 2),
            (finalHeight * scaleRatio / 2)
        )

        canvas.drawBitmap(
            scaleBitmap,
            (finalWidth * scaleRatio - scaleBitmap.width) / 2.toFloat(),
            (finalHeight * scaleRatio - scaleBitmap.height) / 2.toFloat(),
            textPaint
        )
        if (showDebugRect) {
            canvas.restore()
        }
        val bitmapShader = BitmapShader(
            targetBitmap,
            tileMode,
            tileMode
        )
        return@withContext WaterMarkShader(
            bitmapShader,
            targetBitmap.width,
            targetBitmap.height
        )
    }

    /**
     * Draw the watermark cell [shader] over [canvas] — the composition step shared by preview
     * (`onDraw`) and export (`generateImage`). Verbatim unification of the two former branches:
     *
     *  - REPEAT: `translate(left, top)` then fill `[regionWidth] x [regionHeight]` (the whole
     *    image/drawable region is tiled).
     *  - CLAMP : `translate(left + offsetX*regionWidth, top + offsetY*regionHeight)` then draw ONE
     *    cell-sized decal (`shader.width x shader.height`).
     *
     * Preview passes the drawable bounds (`left=drawableBounds.left, top=drawableBounds.top,
     * region=drawableBounds.width()/height()`); export passes `left=0, top=0, region=bitmap size`
     * (its REPEAT branch had no translate — `translate(0,0)` is a no-op, so behavior is identical).
     * The null-shader edge cases (CLAMP → 0x0 rect; REPEAT → paint fills the region) are preserved.
     */
    fun compose(
        canvas: Canvas,
        shader: WaterMarkShader?,
        tileMode: Shader.TileMode,
        paint: Paint,
        left: Float,
        top: Float,
        regionWidth: Float,
        regionHeight: Float,
        offsetX: Float,
        offsetY: Float,
    ) {
        paint.shader = shader?.bitmapShader
        canvas.withSave {
            if (tileMode == Shader.TileMode.CLAMP) {
                translate(left + offsetX * regionWidth, top + offsetY * regionHeight)
                drawRect(
                    0f,
                    0f,
                    (shader?.width ?: 0).toFloat(),
                    (shader?.height ?: 0).toFloat(),
                    paint
                )
            } else {
                translate(left, top)
                drawRect(0f, 0f, regionWidth, regionHeight, paint)
            }
        }
    }
}
