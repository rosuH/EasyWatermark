package me.rosuh.easywatermark.ktx

import android.graphics.BitmapShader
import android.graphics.Color
import android.graphics.Paint
import me.rosuh.easywatermark.config.WaterMarkConfig

fun Paint.applyConfig(config: WaterMarkConfig?): Paint {
    textSize = config?.textSize ?: 14f
    color = config?.textColor ?: Color.RED
    alpha = config?.alpha ?: 128
    style = config?.textStyle ?: Paint.Style.FILL
    isAntiAlias = true
    isDither = true
    return this
}