package me.rosuh.easywatermark

import android.graphics.Color
import android.graphics.Path

data class WaterMarkConfig(
    var text: String = "",
    var textSize: Float = 14f,
    var textColor: Int = Color.RED,
    var alpha: Int = 128,
    var horizonGap: Int = 30,
    var verticalGap: Int = 30,
    var degree: Float = 0f
)