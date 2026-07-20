package me.rosuh.easywatermark.render

import me.rosuh.easywatermark.data.model.ImageFormat
import me.rosuh.easywatermark.data.model.MediaRef
import me.rosuh.easywatermark.data.model.UserPreferences
import me.rosuh.easywatermark.data.model.WaterMark
import java.io.File

/**
 * Immutable per-item Desktop render snapshot (C2).
 *
 * Freezes config, prefs, and CLAMP fractional offset before asynchronous IO. Offsets have **no
 * defaults** — callers must pass the selected [ImageInfo] snapshot or an explicit center for
 * headless/no-session fixtures. Does not own bytes, files, Session, or mutable [ImageInfo].
 */
data class DesktopRenderRequest(
    val config: WaterMark,
    val prefs: UserPreferences,
    val offsetX: Float,
    val offsetY: Float,
)

/**
 * Desktop render-and-write spine: the single Text/Icon compose+encode+write path.
 *
 * Callers choose destination (preview temp, exact path, unique export, headless default) and pass
 * an exact [File]. Synchronous; callers own dispatchers. Paint goes through
 * [DesktopWatermarkComposer.composeRealImage] → [CommonWatermarkPipeline].
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
     * Render [request] over [imageBytes] and write to [target].
     *
     * @throws IllegalArgumentException blank Image-mode icon path ([DesktopSaveDecision.EMPTY_ICON_MESSAGE])
     * @throws IllegalArgumentException missing/unreadable icon file
     * @throws Exception decode/render/encode/IO failures from the composer stack
     */
    fun renderAndSave(
        imageBytes: ByteArray,
        request: DesktopRenderRequest,
        target: File,
    ): DesktopSavedImage {
        val iconBytes: ByteArray? =
            when (val plan = DesktopSaveDecision.renderPlan(request.config.markMode, request.config.iconUri.value)) {
                is DesktopRenderPlan.Icon -> {
                    val iconFile = File(plan.iconPath)
                    require(iconFile.isFile) {
                        "Image-mode icon file is missing or not a regular file: '${plan.iconPath}'"
                    }
                    iconFile.readBytes()
                }
                DesktopRenderPlan.Text -> null
            }
        val composed = DesktopWatermarkComposer.composeRealImage(
            imageBytes = imageBytes,
            request = request,
            iconBytes = iconBytes,
        )
        target.parentFile?.mkdirs()
        target.writeBytes(composed.png)
        return DesktopSavedImage(
            output = MediaRef(target.absolutePath),
            format = request.prefs.outputFormat,
            width = composed.width,
            height = composed.height,
            outputByteCount = composed.png.size,
        )
    }
}
