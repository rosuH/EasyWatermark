package me.rosuh.easywatermark.utils.ktx

import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.text.TextPaint
import me.rosuh.easywatermark.data.model.ImageInfo
import me.rosuh.easywatermark.data.model.WaterMark
import me.rosuh.easywatermark.render.WatermarkRenderer

/**
 * S3a image-space sizing: the text paint size is `textSize * imageInfo.width / REF_WIDTH`, i.e.
 * `textSize` is a fraction (`textSize / REF_WIDTH`) of the target image's width, resolved against the
 * bitmap the cell tiles over (`ImageInfo.width` = the displayed drawable in preview, the full source
 * image at export). Preview and export now use the SAME formula, so the watermark is the same fraction
 * of the image regardless of device/preview-view size.
 *
 * Replaces the old, device-dependent behavior (`textSize` raw in preview; `textSize * scaleX`, where
 * `scaleX = 1/MSCALE_X` from the preview matrix, at export). [isScale] is retained for call-site/source
 * compatibility but **no longer affects sizing** — both modes are image-space. Persisted `textSize`
 * values are unchanged (D3 Option A, no DataStore migration).
 * @author hi@rosuh.me
 * @date 2020/9/8 · S3a image-space re-spec 2026-06-14
 */
fun Paint.applyConfig(
    imageInfo: ImageInfo,
    config: WaterMark?,
    @Suppress("UNUSED_PARAMETER") isScale: Boolean = true
): Paint {
    val size = config?.textSize ?: 14f
    textSize = size * imageInfo.width / WatermarkRenderer.REF_WIDTH
    color = config?.textColor ?: Color.RED
    alpha = config?.alpha ?: 128
    style = config?.textStyle?.obtainSysStyle() ?: Paint.Style.FILL
    typeface =
        Typeface.create(typeface, config?.textTypeface?.obtainSysTypeface() ?: Typeface.NORMAL)
    isAntiAlias = true
    isDither = true
    textAlign = Paint.Align.CENTER
    // todo setShadowLayer(textSize / 2, 0f, 0f, color)
    return this
}

fun TextPaint.applyConfig(
    imageInfo: ImageInfo,
    config: WaterMark?,
    isScale: Boolean = true
): TextPaint {
    return (this as Paint).applyConfig(imageInfo, config, isScale) as TextPaint
}
