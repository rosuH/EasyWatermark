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
                Uri.parse(getString(SP_KEY_URI, "") ?: "")
            } else {
                Uri.parse("")
            }
            val saveText = getString(SP_KEY_TEXT, "")
            text = if (saveText.isNullOrEmpty()) "图片仅供测试，请勿作其他用途" else saveText
            textSize = getFloat(SP_KEY_TEXT_SIZE, 14f)
            textColor = getInt(SP_KEY_TEXT_COLOR, Color.RED)
            alpha = getInt(SP_KEY_ALPHA, 128)
            horizonGapPercent = getInt(SP_KEY_HORIZON_GAP, 30)
            verticalGapPercent = getInt(SP_KEY_VERTICAL_GAP, 30)
            degree = getFloat(SP_KEY_DEGREE, 0f)
            textStyle = when (getInt(SP_KEY_TEXT_STYLE, 0)) {
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
            putString(SP_KEY_URI, uri.toString())
            putString(SP_KEY_TEXT, text)
            putFloat(SP_KEY_TEXT_SIZE, textSize)
            putInt(SP_KEY_TEXT_COLOR, textColor)
            putInt(SP_KEY_ALPHA, alpha)
            putInt(SP_KEY_HORIZON_GAP, horizonGapPercent)
            putInt(SP_KEY_VERTICAL_GAP, verticalGapPercent)
            putFloat(SP_KEY_DEGREE, degree)
            putInt(
                SP_KEY_TEXT_STYLE, when (textStyle) {
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

    companion object {
        const val SP_NAME = "sp_water_mark_config"

        const val SP_KEY_URI = SP_NAME + "_key_uri"
        const val SP_KEY_TEXT = SP_NAME + "_key_text"
        const val SP_KEY_TEXT_SIZE = SP_NAME + "_key_text_size"
        const val SP_KEY_TEXT_COLOR = SP_NAME + "_key_text_color"
        const val SP_KEY_TEXT_STYLE = SP_NAME + "_key_text_style"
        const val SP_KEY_ALPHA = SP_NAME + "_key_alpha"
        const val SP_KEY_HORIZON_GAP = SP_NAME + "_key_horizon_gap"
        const val SP_KEY_VERTICAL_GAP = SP_NAME + "_key_vertical_gap"
        const val SP_KEY_DEGREE = SP_NAME + "_key_degree"
    }
}