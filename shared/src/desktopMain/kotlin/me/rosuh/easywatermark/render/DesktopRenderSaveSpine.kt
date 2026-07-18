package me.rosuh.easywatermark.render

import me.rosuh.easywatermark.data.model.ImageFormat
import me.rosuh.easywatermark.data.model.MediaRef
import me.rosuh.easywatermark.data.model.UserPreferences
import me.rosuh.easywatermark.data.model.WaterMark
import java.io.File

/**
 * Desktop-only **render-and-write** deep module (issue 11 / P1).
 *
 * One Text/Icon/composer/encode/write implementation shared by
 * `DesktopWatermarkFlow.runSaveFlow` and [me.rosuh.easywatermark.session.DesktopExportPipelinePort].
 *
 * **Does not choose destination policy.** Callers pass an exact [target] file (preview temp,
 * Save As path, unique export name, or headless default). The spine only creates parents, writes
 * bytes, and returns observable metadata.
 *
 * Synchronous by design: blocking decode/render/encode/IO. Callers own dispatchers
 * (`Dispatchers.IO` / `runBlocking`).
 */
data class DesktopSavedImage(
    val output: MediaRef,
    val format: ImageFormat,
    val width: Int,
    val height: Int,
    val outputByteCount: Int,
)

object DesktopRenderSaveSpine {

    /**
     * Render [config] over [imageBytes] using [prefs] format/quality and write to [target].
     *
     * @throws IllegalArgumentException blank Image-mode icon path ([DesktopSaveDecision.EMPTY_ICON_MESSAGE])
     * @throws IllegalArgumentException missing/unreadable icon file
     * @throws Exception decode/render/encode/IO failures from the composer stack
     */
    fun renderAndSave(
        imageBytes: ByteArray,
        config: WaterMark,
        prefs: UserPreferences,
        target: File,
    ): DesktopSavedImage {
        val composed: DesktopWatermarkComposer.ComposedImage =
            when (val plan = DesktopSaveDecision.renderPlan(config.markMode, config.iconUri.value)) {
                is DesktopRenderPlan.Icon -> {
                    val iconFile = File(plan.iconPath)
                    require(iconFile.isFile) {
                        "Image-mode icon file is missing or not a regular file: '${plan.iconPath}'"
                    }
                    DesktopWatermarkComposer.composeIconOverRealImage(
                        imageBytes = imageBytes,
                        iconBytes = iconFile.readBytes(),
                        tileMode = config.tileMode,
                        textSize = config.textSize,
                        degree = config.degree,
                        hGapPercent = config.hGap,
                        vGapPercent = config.vGap,
                        alpha = config.alpha / 255f,
                        format = prefs.outputFormat,
                        quality = prefs.compressLevel,
                    )
                }
                DesktopRenderPlan.Text -> DesktopWatermarkComposer.composeOverRealImage(
                    imageBytes = imageBytes,
                    text = config.text,
                    tileMode = config.tileMode,
                    textSize = config.textSize,
                    degree = config.degree,
                    hGapPercent = config.hGap,
                    vGapPercent = config.vGap,
                    alpha = config.alpha / 255f,
                    colorArgb = config.textColor,
                    typeface = config.textTypeface,
                    textStyle = config.textStyle,
                    format = prefs.outputFormat,
                    quality = prefs.compressLevel,
                )
            }
        target.parentFile?.mkdirs()
        target.writeBytes(composed.png)
        return DesktopSavedImage(
            output = MediaRef(target.absolutePath),
            format = prefs.outputFormat,
            width = composed.width,
            height = composed.height,
            outputByteCount = composed.png.size,
        )
    }
}
