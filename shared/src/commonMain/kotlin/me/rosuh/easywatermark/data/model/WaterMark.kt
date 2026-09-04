package me.rosuh.easywatermark.data.model

/**
 * Platform-neutral watermark configuration persisted by [WaterMarkRepository].
 *
 * Field names, types, and defaults are compatibility-critical for DataStore. Platform types
 * (`android.graphics.*`) stay at Android edge mappers.
 */
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
    val iconUri: MediaRef,
    val markMode: WatermarkMode,
    val enableBounds: Boolean,
    val tileMode: WatermarkTileMode,
) {
    companion object {
        val default = WaterMark(
            text = "👋 DO NOT REDISTRIBUTE",
            textSize = (14f).coerceAtLeast(1f),
            // android.graphics.Color.parseColor("#FFB800") == 0xFFFFB800 (alpha FF prepended).
            textColor = 0xFFFFB800.toInt(),
            textStyle = TextPaintStyle.obtainSealedClass(0),
            textTypeface = TextTypeface.obtainSealedClass(0),
            alpha = 255,
            degree = 315f,
            hGap = 0,
            vGap = 0,
            iconUri = MediaRef.Empty,
            markMode = WatermarkMode.Text,
            enableBounds = false,
            tileMode = WatermarkTileMode.REPEAT,
        )
    }
}
