package me.rosuh.easywatermark.session

import me.rosuh.easywatermark.data.model.ImageInfo
import me.rosuh.easywatermark.data.model.MediaRef
import me.rosuh.easywatermark.data.model.Result
import me.rosuh.easywatermark.data.model.UserPreferences
import me.rosuh.easywatermark.data.model.WaterMark

/**
 * Platform export seam (ADR-0017 Phase 2).
 *
 * Decodes the source [ImageInfo], applies [config], encodes, and persists the result.
 * Android production uses commonMain raster via `AndroidCommonRaster` (ADR-0018); native
 * `WatermarkRenderer` is measurement/golden oracle only. Desktop/iOS bind Skiko pipelines over the
 * same common compose core.
 *
 * May mutate [imageInfo] width/height as the legacy path did after decode.
 */
fun interface ExportPipelinePort {
    suspend fun exportOne(
        imageInfo: ImageInfo,
        config: WaterMark,
        prefs: UserPreferences,
    ): Result<MediaRef>
}

/** Stable error codes for export failures (match legacy MainViewModel companion strings). */
object ExportErrorCodes {
    const val FILE_NOT_FOUND = "type_error_file_not_found"
    const val SAVE_OOM = "type_error_save_oom"
}
