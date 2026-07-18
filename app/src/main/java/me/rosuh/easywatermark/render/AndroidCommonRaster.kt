package me.rosuh.easywatermark.render

import android.content.Context
import android.graphics.Bitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.font.createFontFamilyResolver
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import me.rosuh.easywatermark.data.model.ImageInfo
import me.rosuh.easywatermark.data.model.WaterMark
import me.rosuh.easywatermark.data.model.WatermarkMode

/**
 * Android edge for ADR-0018 common 光栅: [TextRasterEnv] bootstrap + [Bitmap] ↔ [ImageBitmap].
 * Production preview and export always call [composeToBitmap] (rollout flag removed).
 */
object AndroidCommonRaster {

    fun textRasterEnv(context: Context): TextRasterEnv = TextRasterEnv(
        fontFamilyResolver = createFontFamilyResolver(context),
        density = Density(1f),
        layoutDirection = LayoutDirection.Ltr,
    )

    /**
 * Compose [config] over a copy of [background] (ARGB_8888). Optional [icon] for Image mode.
 * Off-main only — call from IO/Default, never Compose Main.
     */
    fun composeToBitmap(
        context: Context,
        background: Bitmap,
        config: WaterMark,
        imageInfo: ImageInfo,
        icon: Bitmap? = null,
    ): Bitmap {
        val bg = if (background.config == Bitmap.Config.ARGB_8888 && background.isMutable) {
            background
        } else {
            background.copy(Bitmap.Config.ARGB_8888, /* mutable = */ false)
                ?: error("AndroidCommonRaster: cannot copy background bitmap")
        }
        val env = textRasterEnv(context)
        val iconIb = icon?.asImageBitmap()
        if (config.markMode == WatermarkMode.Image) {
            require(iconIb != null) { "AndroidCommonRaster: Image mode requires icon bitmap" }
        }
        val composed = CommonWatermarkPipeline.compose(
            background = bg.asImageBitmap(),
            config = config,
            env = env,
            icon = iconIb,
            offsetX = imageInfo.offsetX,
            offsetY = imageInfo.offsetY,
        )
        return composed.asAndroidBitmap()
    }

    /**
 * Image-space cell width/height for CLAMP hit-testing under common preview.
 * Off-main only.
     */
    fun cellSizePx(
        context: Context,
        config: WaterMark,
        imageInfo: ImageInfo,
        icon: Bitmap? = null,
    ): Pair<Int, Int> {
        val env = textRasterEnv(context)
        val cell = when (config.markMode) {
            WatermarkMode.Text -> CommonWatermarkPipeline.composeTextCell(
                imageWidth = imageInfo.width.coerceAtLeast(1),
                config = config,
                env = env,
            )
            WatermarkMode.Image -> {
                val iconIb = icon?.asImageBitmap()
                    ?: error("AndroidCommonRaster.cellSizePx: Image mode requires icon bitmap")
                CommonWatermarkPipeline.composeIconCell(config, iconIb)
            }
        }
        return cell.width to cell.height
    }
}
