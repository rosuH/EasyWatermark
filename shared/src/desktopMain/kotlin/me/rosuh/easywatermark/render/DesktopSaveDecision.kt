package me.rosuh.easywatermark.render

import me.rosuh.easywatermark.data.model.ImageFormat
import me.rosuh.easywatermark.data.model.WatermarkMode
import java.io.File

/**
 * Pure Desktop save/export decision helpers (no decode/compose/write).
 *
 * Used by [DesktopRenderSaveSpine], [DesktopExportPipelinePort], and `DesktopWatermarkFlow`.
 */
sealed interface DesktopRenderPlan {
    /** Text watermark — `WaterMark.markMode == Text`. */
    object Text : DesktopRenderPlan

    /** Image/icon watermark over the persisted icon file [iconPath] (validated non-empty; existence is IO). */
    data class Icon(val iconPath: String) : DesktopRenderPlan
}

object DesktopSaveDecision {

    /** The exact loud-fail message for Image mode with no persisted icon path (moved verbatim from the flow). */
    const val EMPTY_ICON_MESSAGE: String =
        "Image-mode watermark has no persisted iconUri; refusing to render (no silent fallback to Text)."

    /**
     * Choose the render plan from the persisted [markMode] + [iconPath] (= `WaterMark.iconUri.value`).
     * Image mode with a **blank** icon path fails loudly with [EMPTY_ICON_MESSAGE] — no silent
     * fallback to Text. Icon **file** existence is IO and lives in [DesktopRenderSaveSpine]. Pure (no IO).
     */
    fun renderPlan(markMode: WatermarkMode, iconPath: String): DesktopRenderPlan = when (markMode) {
        WatermarkMode.Image -> {
            require(iconPath.isNotEmpty()) { EMPTY_ICON_MESSAGE }
            DesktopRenderPlan.Icon(iconPath)
        }
        WatermarkMode.Text -> DesktopRenderPlan.Text
    }

    /**
 * The default output filename for [format]: JPEG → `watermarked.jpg`, PNG → `watermarked.png`. Pure.
 * Extension single-sourced on [ImageFormat.fileExtension] (was an inline `if` here).     */
    fun defaultOutputFileName(format: ImageFormat): String =
        "watermarked." + format.fileExtension

    /**
 * Returns a file in [dir] whose name does not collide with an existing file.
 *
 * Base name is `watermarked.<format.fileExtension>`. If that file already exists, the smallest
 * Available suffix `n >= 1` is chosen: `watermarked_n.<ext>`. The helper performs filesystem * existence checks only and does not create, write, or delete files.
 *
 * Examples:
 * - empty dir → `watermarked.jpg`
 * - base exists → `watermarked_1.jpg`
 * - base + `_1` exist → `watermarked_2.jpg`
 * - base + `_2` exist while `_1` is free → `watermarked_1.jpg`
     */
    fun resolveUniqueOutputFile(dir: File, format: ImageFormat): File {
        val ext = format.fileExtension
        val base = File(dir, "watermarked.$ext")
        if (!base.exists()) return base
        var n = 1
        while (true) {
            val candidate = File(dir, "watermarked_${n}.$ext")
            if (!candidate.exists()) return candidate
            n++
        }
    }

    /**
 * Whether the save composites over caller-provided bytes (`true`) or the generated fixture (`false`).
 * This is the pure form of the flow's `inputBytes ?: fixture` idiom — represented here so the rule is
 * Testable without IO. Pure.     */
    fun usesCallerInput(inputBytes: ByteArray?): Boolean = inputBytes != null

    /**
 * The supported image files among [files] — those whose extension (lower-cased) is in [extensions],
 * With the original order preserved. Pure (no IO). *
 * the Desktop window's multi-file drag/drop batch selects every supported dropped image (was
 * a single-file `.firstOrNull`). This pure filter lives here so the selection is unit-testable in
 * `:shared:desktopTest`; the AWT `javaFileListFlavor` extraction stays in `:desktopApp`.
     */
    fun supportedImageFiles(files: List<File>, extensions: Set<String>): List<File> =
        files.filter { it.extension.lowercase() in extensions }
}
