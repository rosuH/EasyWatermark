package me.rosuh.easywatermark.model

import android.content.Context.MODE_PRIVATE
import android.content.SharedPreferences
import android.graphics.Color
import android.graphics.Paint
import android.net.Uri
import android.os.Build
import androidx.core.content.edit
import me.rosuh.easywatermark.MyApp
import me.rosuh.easywatermark.R

class WaterMarkConfig private constructor() {
    lateinit var uri: Uri

    lateinit var text: String

    var textSize: Float = 0.0f
        set(value) {
            field = value.coerceAtLeast(0f).coerceAtMost(MAX_TEXT_SIZE)
        }
    var textColor: Int = Color.RED

    var alpha: Int = 255
        set(value) {
            field = value.coerceAtLeast(0).coerceAtMost(255)
        }

    var horizonGapPercent: Int = 30
        set(value) {
            field = value.coerceAtLeast(0).coerceAtMost(MAX_HORIZON_GAP)
        }

    var verticalGapPercent: Int = 30
        set(value) {
            field = value.coerceAtLeast(0).coerceAtMost(MAX_VERTICAL_GAP)
        }

    var degree: Float = 315f
        set(value) {
            field = value.coerceAtLeast(0f).coerceAtMost(MAX_DEGREE)
        }

    lateinit var textStyle: Paint.Style

    lateinit var iconUri: Uri

    lateinit var markMode: MarkMode

    var imageScale: FloatArray = floatArrayOf(1f, 1f)

    fun restore(
        sharedPreferences: SharedPreferences = MyApp.instance.getSharedPreferences(
            SP_NAME,
            MODE_PRIVATE
        )
    ) {
        with(sharedPreferences) {
            uri = Uri.parse("")
            val saveText = getString(SP_KEY_TEXT, "")
            text =
                if (saveText.isNullOrEmpty()) MyApp.instance.getString(R.string.config_default_water_mark_text) else saveText
            textSize = getFloat(SP_KEY_TEXT_SIZE, 18f)
            textColor = getInt(SP_KEY_TEXT_COLOR, Color.parseColor("#FFB800"))
            alpha = getInt(SP_KEY_ALPHA, 255)
            horizonGapPercent = getInt(SP_KEY_HORIZON_GAP, 30)
            verticalGapPercent = getInt(SP_KEY_VERTICAL_GAP, 30)
            degree = getFloat(SP_KEY_DEGREE, 315f)
            textStyle = when (getInt(SP_KEY_TEXT_STYLE, 0)) {
                0 -> {
                    Paint.Style.FILL
                }
                else -> {
                    Paint.Style.STROKE
                }
            }
            markMode = when (getInt(SP_KEY_MODE, 0)) {
                0 -> {
                    MarkMode.Text
                }
                else -> {
                    MarkMode.Image
                }
            }

            iconUri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                Uri.parse(getString(SP_KEY_ICON_URI, "") ?: "")
            } else {
                Uri.parse("")
            }
            imageScale[0] = getFloat(SP_KEY_IMAGE_SCALE_X, 1f)
            imageScale[1] = getFloat(SP_KEY_IMAGE_SCALE_Y, imageScale[0])
        }
    }

    fun save(sharedPreferences: SharedPreferences = MyApp.globalSp()) {
        sharedPreferences.edit {
            putString(SP_KEY_ICON_URI, iconUri.toString())
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
            putInt(
                SP_KEY_MODE, when (markMode) {
                    MarkMode.Text -> 0
                    MarkMode.Image -> 1
                }
            )
            putFloat(SP_KEY_IMAGE_SCALE_X, imageScale[0])
            putFloat(SP_KEY_IMAGE_SCALE_Y, imageScale[1])
        }
    }

    fun canDrawText(): Boolean {
        return markMode == MarkMode.Text && text.isNotEmpty()
    }

    fun canDrawIcon(): Boolean {
        return markMode == MarkMode.Image && iconUri.toString().isNotEmpty()
    }

    fun canDraw(): Boolean {
        return canDrawText() || canDrawIcon()
    }

    sealed class MarkMode {
        object Text : MarkMode()

        object Image : MarkMode()
    }

    override fun toString(): String {
        return """
            uri: $uri, 
            text: $text,
            textSize: $textSize,
            textColor: $textColor,
            alpha: $alpha,
            horizonGapPercent: $horizonGapPercent,
            verticalGapPercent: $verticalGapPercent,
            degree: $degree,
            textStyle: $textStyle,
            iconUri: $iconUri,
            markMode: $markMode,
            imageScale: $imageScale
        """.trimIndent()
    }


    companion object {
        const val SP_NAME = "sp_water_mark_config"

        const val SP_KEY_TEXT = "${SP_NAME}_key_text"
        const val SP_KEY_TEXT_SIZE = "${SP_NAME}_key_text_size"
        const val SP_KEY_TEXT_COLOR = "${SP_NAME}_key_text_color"
        const val SP_KEY_TEXT_STYLE = "${SP_NAME}_key_text_style"
        const val SP_KEY_ALPHA = "${SP_NAME}_key_alpha"
        const val SP_KEY_HORIZON_GAP = "${SP_NAME}_key_horizon_gap"
        const val SP_KEY_VERTICAL_GAP = "${SP_NAME}_key_vertical_gap"
        const val SP_KEY_DEGREE = "${SP_NAME}_key_degree"
        const val SP_KEY_ICON_URI = "${SP_NAME}_key_icon_uri"
        const val SP_KEY_MODE = "${SP_NAME}_key_type"
        const val SP_KEY_CHANGE_LOG = "${SP_NAME}_key_change_log"
        const val SP_KEY_IMAGE_SCALE_X = "${SP_NAME}_key_image_scale"
        const val SP_KEY_IMAGE_SCALE_Y = "${SP_NAME}_key_image_scale_y"

        const val MAX_TEXT_SIZE = 100f
        const val MAX_DEGREE = 360f
        const val MAX_HORIZON_GAP = 500
        const val MAX_VERTICAL_GAP = 500

        fun pull(
            sp: SharedPreferences = MyApp.globalSp()
        ): WaterMarkConfig {
            return WaterMarkConfig().apply {
                restore(sp)
            }
        }
    }
}