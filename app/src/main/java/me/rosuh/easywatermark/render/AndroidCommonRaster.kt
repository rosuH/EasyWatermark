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
 * Export always calls [composeToBitmap]. Editor main preview uses [composeCell] (ADR-0033).
 */
object AndroidCommonRaster {

    @Volatile
    private var memoResolver: androidx.compose.ui.text.font.FontFamily.Resolver? = null

    @Volatile
    private var memoResolverContext: Context? = null

    fun textRasterEnv(context: Context): TextRasterEnv {
        val app = context.applicationContext
        val cached = memoResolver
        val resolver = if (cached != null && memoResolverContext === app) {
            cached
        } else {
            createFontFamilyResolver(app).also {
                memoResolver = it
                memoResolverContext = app
            }
        }
        return TextRasterEnv(
            fontFamilyResolver = resolver,
            density = Density(1f),
            layoutDirection = LayoutDirection.Ltr,
        )
    }

    /**
     * Compose [config] over [background]. Optional [icon] for Image mode.
     * [composeOverBackground] only reads the source and allocates its own output — no copy.
     * Off-main only — call from IO/Default, never Compose Main.
     */
    fun composeToBitmap(
        context: Context,
        background: Bitmap,
        config: WaterMark,
        imageInfo: ImageInfo,
        icon: Bitmap? = null,
    ): Bitmap {
        PreviewSourceReuseProbe.beginCompose()
        return try {
            val env = textRasterEnv(context)
            val iconIb = icon?.asImageBitmap()
            if (config.markMode == WatermarkMode.Image) {
                require(iconIb != null) { "AndroidCommonRaster: Image mode requires icon bitmap" }
            }
            PreviewSourceReuseProbe.recordCompose()
            val composed = CommonWatermarkPipeline.compose(
                background = background.asImageBitmap(),
                config = config,
                env = env,
                icon = iconIb,
                offsetX = imageInfo.offsetX,
                offsetY = imageInfo.offsetY,
            )
            composed.asAndroidBitmap()
        } finally {
            PreviewSourceReuseProbe.endCompose()
        }
    }

    /**
     * Overlay cell only (ADR-0033). Off-main. Export still uses [composeToBitmap].
     */
    fun composeCell(
        context: Context,
        config: WaterMark,
        imageWidth: Int,
        icon: Bitmap? = null,
    ): androidx.compose.ui.graphics.ImageBitmap {
        val env = textRasterEnv(context)
        val iconIb = icon?.asImageBitmap()
        if (config.markMode == WatermarkMode.Image) {
            require(iconIb != null) { "AndroidCommonRaster.composeCell: Image mode requires icon bitmap" }
        }
        PreviewSourceReuseProbe.recordCompose()
        return CommonWatermarkPipeline.composeCell(
            imageWidth = imageWidth.coerceAtLeast(1),
            config = config,
            env = env,
            icon = iconIb,
        )
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
