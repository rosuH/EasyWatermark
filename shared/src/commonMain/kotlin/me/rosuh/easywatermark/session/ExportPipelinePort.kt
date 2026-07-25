package me.rosuh.easywatermark.session

import me.rosuh.easywatermark.data.model.ImageInfo
import me.rosuh.easywatermark.data.model.UserPreferences
import me.rosuh.easywatermark.data.model.WaterMark

/**
 * Platform export seam (ADR-0017 Phase 2; Stage D / D1 typed outcome).
 *
 * Decodes the source [ImageInfo], applies [config], encodes, and persists the result.
 * Android production uses commonMain raster via `AndroidCommonRaster` (ADR-0018); native
 * `WatermarkRenderer` is measurement/golden oracle only. Desktop/iOS bind Skiko pipelines over the
 * same common compose core.
 *
 * Returns [ExportOutcome]: success is immutable [me.rosuh.easywatermark.data.model.ExportedMedia]
 * facts (ref/dims/format/bytes). May still mutate [imageInfo] width/height for legacy UI, but
 * that mutation must not be the sole source of success identity.
 */
fun interface ExportPipelinePort {
    suspend fun exportOne(
        imageInfo: ImageInfo,
        config: WaterMark,
        prefs: UserPreferences,
    ): ExportOutcome
}

/**
 * Stable error codes for export failures (match legacy MainViewModel companion strings where
 * historical; D1 adds taxonomy codes for non-collapsed failures).
 */
object ExportErrorCodes {
    const val FILE_NOT_FOUND = "type_error_file_not_found"
    const val SAVE_OOM = "type_error_save_oom"
    const val SOURCE_DECODE = "type_error_source_decode"
    const val RENDER = "type_error_render"
    const val ENCODE = "type_error_encode"
    const val PERMISSION = "type_error_permission"
    const val IO = "type_error_io"
    const val PERSISTENCE = "type_error_persistence"
    const val CANCELLED = "type_error_cancelled"
}
