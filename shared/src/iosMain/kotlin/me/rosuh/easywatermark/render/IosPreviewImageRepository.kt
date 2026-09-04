package me.rosuh.easywatermark.render

import androidx.compose.ui.graphics.ImageBitmap
import kotlinx.coroutines.CoroutineScope

internal typealias IosPreviewKey = PreviewKey
internal typealias IosPreviewPurpose = PreviewPurpose
internal typealias IosPreviewRepositorySnapshot = PreviewRepositorySnapshot

/**
 * iOS host facade over the common [PreviewImageRepository].
 *
 * Zero behavior change vs the pre-lift iosMain copy: same defaults, same ImageBitmap
 * byte model, same mutex/LRU/joint-eviction. Decode stays in ImageIO / PhotoKit edges.
 *
 * J5: internal — not a Swift product API.
 */
internal class IosPreviewImageRepository(
    ownerScope: CoroutineScope,
    sourceAndPreviewBytesMax: Long = SOURCE_AND_PREVIEW_BYTES_MAX,
    filmstripBytesMax: Long = FILMSTRIP_BYTES_MAX,
    watermarkedEntriesMax: Int = DEFAULT_WATERMARKED_ENTRIES_MAX,
    sourcePlaceholderEntriesMax: Int = DEFAULT_SOURCE_PLACEHOLDER_ENTRIES_MAX,
    filmstripEntriesMax: Int = DEFAULT_FILMSTRIP_ENTRIES_MAX,
    exportThumbnailEntriesMax: Int = DEFAULT_EXPORT_THUMBNAIL_ENTRIES_MAX,
    sourceFastPathEntriesMax: Int = DEFAULT_SOURCE_FAST_PATH_ENTRIES_MAX,
    watermarkedBytesMax: Long = DEFAULT_WATERMARKED_BYTES_MAX,
    sourcePlaceholderBytesMax: Long = DEFAULT_SOURCE_PLACEHOLDER_BYTES_MAX,
    exportThumbnailBytesMax: Long = DEFAULT_EXPORT_THUMBNAIL_BYTES_MAX,
    sourceFastPathBytesMax: Long = DEFAULT_SOURCE_FAST_PATH_BYTES_MAX,
) : PreviewImageRepository<ImageBitmap>(
    ownerScope = ownerScope,
    approxBytes = Companion::approxBytes,
    sourceAndPreviewBytesMax = sourceAndPreviewBytesMax,
    filmstripBytesMax = filmstripBytesMax,
    watermarkedEntriesMax = watermarkedEntriesMax,
    sourcePlaceholderEntriesMax = sourcePlaceholderEntriesMax,
    filmstripEntriesMax = filmstripEntriesMax,
    exportThumbnailEntriesMax = exportThumbnailEntriesMax,
    sourceFastPathEntriesMax = sourceFastPathEntriesMax,
    watermarkedBytesMax = watermarkedBytesMax,
    sourcePlaceholderBytesMax = sourcePlaceholderBytesMax,
    exportThumbnailBytesMax = exportThumbnailBytesMax,
    sourceFastPathBytesMax = sourceFastPathBytesMax,
) {
    companion object {
        /**
         * Constructor-default joint floor (R1). Live Host caps come from
         * [PreviewWorkingSetBudget] for the current preview long-edge.
         */
        const val SOURCE_AND_PREVIEW_BYTES_MAX: Long = 64L * 1024 * 1024
        const val FILMSTRIP_BYTES_MAX: Long = 8L * 1024 * 1024
        /**
         * Entry cap stays 48; byte caps follow the current preview long-edge.
         */
        const val DEFAULT_WATERMARKED_ENTRIES_MAX: Int = 48
        const val DEFAULT_SOURCE_PLACEHOLDER_ENTRIES_MAX: Int = 12
        const val DEFAULT_FILMSTRIP_ENTRIES_MAX: Int = 48
        const val DEFAULT_EXPORT_THUMBNAIL_ENTRIES_MAX: Int = 48
        /** Focus ±1 chrome only (ADR-0029). */
        const val DEFAULT_SOURCE_FAST_PATH_ENTRIES_MAX: Int = 3
        /** Watermarked purpose floor — 720 panes stay at 48 MiB. */
        const val DEFAULT_WATERMARKED_BYTES_MAX: Long = 48L * 1024 * 1024
        const val DEFAULT_SOURCE_PLACEHOLDER_BYTES_MAX: Long = 12L * 1024 * 1024
        const val DEFAULT_EXPORT_THUMBNAIL_BYTES_MAX: Long = 8L * 1024 * 1024
        const val DEFAULT_SOURCE_FAST_PATH_BYTES_MAX: Long = 12L * 1024 * 1024

        fun approxBytes(bitmap: ImageBitmap): Long =
            PreviewImageRepository.approxImageBitmapBytes(bitmap)
    }
}
