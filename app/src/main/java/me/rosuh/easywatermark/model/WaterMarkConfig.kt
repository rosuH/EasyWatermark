package me.rosuh.easywatermark.model

import android.content.Context.MODE_PRIVATE
import android.graphics.Color
import android.graphics.Paint
import android.net.Uri
import android.os.Build
import androidx.core.content.edit
import me.rosuh.easywatermark.MyApp
import kotlin.math.pow
import kotlin.math.sqrt

class WaterMarkConfig {
    var uri: Uri

    var text: String

    var textSize: Float

    var textColor: Int

    var alpha: Int

    var horizonGapPercent: Int

    var verticalGapPercent: Int

    var degree: Float

    var textStyle: Paint.Style

    init {
        with(
            MyApp.instance.getSharedPreferences(SP_NAME, MODE_PRIVATE)
        ) {
            uri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                Uri.parse(getString("uri", "") ?: "")
            } else{
                Uri.parse("")
            }
            val saveText = getString("text", "")
            text = if (saveText.isNullOrEmpty()) "图片仅供测试，请勿作其他用途" else saveText
            textSize = getFloat("text_size", 14f)
            textColor = getInt("text_color", Color.RED)
            alpha = getInt("alpha", 128)
            horizonGapPercent = getInt("horizon_gap", 30)
            verticalGapPercent = getInt("vertical_gap", 30)
            degree = getFloat("degree", 0f)
            textStyle = when (getInt("text_style", 0)) {
                0 -> {
                    Paint.Style.FILL
                }
                else -> {
                    Paint.Style.STROKE
                }
            }
        }
    }

    fun save() {
        MyApp.instance.getSharedPreferences(
            SP_NAME, MODE_PRIVATE
        ).edit {
            putString("uri", uri.toString())
            putString("text", text)
            putFloat("text_size", textSize)
            putInt("text_color", textColor)
            putInt("alpha", alpha)
            putInt("horizon_gap", horizonGapPercent)
            putInt("vertical_gap", verticalGapPercent)
            putFloat("degree", degree)
            putInt(
                "text_style", when (textStyle) {
                    Paint.Style.FILL -> {
                        0
                    }
                    else -> {
                        1
                    }
                }
            )
        }
    }

    fun calculateHorizon(width: Int): Int {
        return (horizonGapPercent / 100.0 * width).toInt().coerceAtLeast(0)
            .coerceAtMost(width)
    }

    fun calculateVertical(height: Int): Int {
        return (verticalGapPercent / 100.0 * height).toInt().coerceAtLeast(0)
            .coerceAtMost(height)
    }

    companion object {
        const val SP_NAME = "sp_water_mark_config"

        fun calculateCircleRadius(width: Int, height: Int): Float {
            return sqrt(width.toFloat().pow(2) + height.toFloat().pow(2))
        }
    }
}