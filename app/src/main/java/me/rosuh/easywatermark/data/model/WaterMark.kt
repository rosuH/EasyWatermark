package me.rosuh.easywatermark.data.model

import android.graphics.Shader
import android.net.Uri
import android.os.Build
import androidx.annotation.Keep
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
}
