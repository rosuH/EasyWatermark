package me.rosuh.easywatermark.render

import me.rosuh.easywatermark.data.model.ImageFormat
import me.rosuh.easywatermark.data.model.WatermarkMode
import java.io.File

/**
 * S4d-139 / S4d-222: the Desktop save-flow decision seam, extracted from
 * `DesktopWatermarkFlow.runSaveFlow` (`:desktopApp`) so it is unit-testable in `:shared:desktopTest`
 * — which cannot see `:desktopApp`. This is the smallest runtime-harness seam for the Desktop flow glue
 * (S4d-138 Q4), with **no new dependency** and **no behavior change**.
 *
 * Most decisions here are pure (no IO): which render path, the default output filename for a format,
 * and whether the caller supplied input bytes. DataStore reads, the icon FILE-existence check, the
 * `DesktopWatermarkComposer` calls, and the disk write all stay in `runSaveFlow`.
 *
 * S4d-222 adds [resolveUniqueOutputFile], a desktopMain filesystem helper that performs existence
 * checks only and does not create, write, or delete files.
 *
 * The Image-mode blank-icon loud-fail message is moved here **verbatim** from the inline `require` it
 * replaces, so the flow's behavior is byte-for-byte the same (the missing-FILE check, which is IO,
 * stays in the flow and keeps its own message). See `DesktopSaveDecisionTest`.
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
     * Image mode with a **blank** icon path fails loudly with [EMPTY_ICON_MESSAGE] — no silent fallback to
     * Text. The icon FILE-existence check is IO and stays in `runSaveFlow`. Pure (no IO).
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
     * S4d-177: extension single-sourced on [ImageFormat.fileExtension] (was an inline `if` here).
     */
    fun defaultOutputFileName(format: ImageFormat): String =
        "watermarked." + format.fileExtension

    /**
     * Returns a file in [dir] whose name does not collide with an existing file.
     *
     * Base name is `watermarked.<format.fileExtension>`. If that file already exists, the smallest
     * available suffix `n >= 1` is chosen: `watermarked_n.<ext>`. The helper performs filesystem
     * existence checks only and does not create, write, or delete files.
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
     * testable without IO. Pure.
     */
    fun usesCallerInput(inputBytes: ByteArray?): Boolean = inputBytes != null
}
