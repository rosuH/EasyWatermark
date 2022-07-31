package me.rosuh.easywatermark.data.model

import android.graphics.Shader
import android.net.Uri
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
    val tileMode: Int,
    val offsetX: Int,
    val offsetY: Int,
) {
    fun obtainTileMode(): Shader.TileMode {
        return when (tileMode) {
            Shader.TileMode.CLAMP.ordinal -> Shader.TileMode.CLAMP
            Shader.TileMode.MIRROR.ordinal -> Shader.TileMode.MIRROR
            Shader.TileMode.REPEAT.ordinal -> Shader.TileMode.REPEAT
            else -> Shader.TileMode.DECAL
        }
    }
}
