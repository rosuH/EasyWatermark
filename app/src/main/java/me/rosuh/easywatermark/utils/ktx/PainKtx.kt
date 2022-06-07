package me.rosuh.easywatermark.utils.ktx

import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.text.TextPaint
import me.rosuh.easywatermark.data.model.ImageInfo
import me.rosuh.easywatermark.data.model.WaterMark

/**
 * 因为预览和实际图像之间存在缩放，所以在预览时要除去缩放比。而在保存时，就不需要了
 * Because there is a zoom between the preview and the actual image, the zoom ratio should be removed when previewing
 * When saving, it’s not needed
 * @author hi@rosuh.me
 * @date 2020/9/8
 */
fun Paint.applyConfig(
    imageInfo: ImageInfo,
    config: WaterMark?,
    isScale: Boolean = true
): Paint {
    val size = config?.textSize ?: 14f
    textSize = if (isScale) size else size * imageInfo.scaleX
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
