package me.rosuh.easywatermark.data.model

import android.graphics.Color
import android.graphics.Shader
import android.net.Uri
import android.os.Build
import androidx.annotation.Keep
import me.rosuh.easywatermark.MyApp
import me.rosuh.easywatermark.R
import me.rosuh.easywatermark.data.repo.WaterMarkRepository

@Keep
data class WaterMark(
    val text: String,
    val textSize: Float,
    val textColor: Int,
    val textStyle: TextPaintStyle,
    val textTypeface: TextTypeface,
    val alpha: Int,
    val degree: Float,
    val hGap: Int,
    val vGap: Int,
    val iconUri: Uri,
    val markMode: WaterMarkRepository.MarkMode,
    val enableBounds: Boolean,
    val tileMode: Shader.TileMode,
) {
    fun obtainTileMode(): Shader.TileMode {
        return tileMode
	}
    
	companion object {
        val default = WaterMark(
            text = "\uD83D\uDC4B DO NOT REDISTRIBUTE",
            textSize = (14f).coerceAtLeast(1f),
            textColor = Color.parseColor("#FFB800"),
            textStyle = TextPaintStyle.obtainSealedClass(0),
            textTypeface = TextTypeface.obtainSealedClass(0),
            alpha = 255,
            degree = 315f,
            hGap = 0,
            vGap = 0,
            iconUri = Uri.parse(""),
            markMode = WaterMarkRepository.MarkMode.Text,
            enableBounds = false,
            tileMode = Shader.TileMode.REPEAT,
        )
    }
}
