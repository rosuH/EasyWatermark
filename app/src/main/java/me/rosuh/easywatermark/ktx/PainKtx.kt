package me.rosuh.easywatermark.ktx

import android.graphics.Color
import android.graphics.Paint
import me.rosuh.easywatermark.model.WaterMarkConfig

/**
 * @author rosuh@qq.com
 * @date 2020/9/8
 * 因为预览和实际图像之间存在缩放，所以在预览时要除去缩放比。而在保存时，就不需要了
 * Because there is a zoom between the preview and the actual image, the zoom ratio should be removed when previewing
 * When saving, it’s not needed
 */
fun Paint.applyConfig(
    config: WaterMarkConfig?,
    scale: Boolean = true
): Paint {
    textSize = (config?.textSize ?: 14f) / if (scale) {
        (config?.imageScale ?: 1f)
    } else {
        1f
    }
    color = config?.textColor ?: Color.RED
    alpha = config?.alpha ?: 128
    style = config?.textStyle ?: Paint.Style.FILL
    isAntiAlias = true
    isDither = true
    return this
}