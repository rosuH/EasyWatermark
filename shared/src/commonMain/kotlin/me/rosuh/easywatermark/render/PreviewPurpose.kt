package me.rosuh.easywatermark.render

import kotlin.experimental.ExperimentalObjCRefinement
import kotlin.native.HiddenFromObjC

/**
 * Product use of a cached preview frame.
 *
 * [SourceFastPath] is **iOS-only chrome** (ADR-0029 Library derivative). Android and Desktop
 * must never write it and must never compose from it.
 *
 * [Filmstrip] is a leftover / test slot. Production filmstrip thumbs are Coil (ADR-0028).
 */
@OptIn(ExperimentalObjCRefinement::class)
@HiddenFromObjC
enum class PreviewPurpose {
    SourcePlaceholder,
    Watermarked,
    Filmstrip,
    ExportThumbnail,
    /** Unwatermarked Library derivative chrome (ADR-0029). Never a compose background. */
    SourceFastPath,
}

/** Decode identity: same path + bucket + purpose always shares one cold request. */
@OptIn(ExperimentalObjCRefinement::class)
@HiddenFromObjC
data class PreviewKey(
    val ownedPath: String,
    val pixelBucket: Int,
    val purpose: PreviewPurpose,
)

@OptIn(ExperimentalObjCRefinement::class)
@HiddenFromObjC
data class PreviewRepositorySnapshot(
    val cachedEntries: Int,
    val inFlightEntries: Int,
    val previewBytes: Long,
    val filmstripBytes: Long,
    val closed: Boolean,
    val watermarkedEntries: Int = 0,
    val sourcePlaceholderEntries: Int = 0,
    val filmstripEntries: Int = 0,
    val exportThumbnailEntries: Int = 0,
    val watermarkedBytes: Long = 0,
    val sourcePlaceholderBytes: Long = 0,
    val exportThumbnailBytes: Long = 0,
    val cachedKeys: Set<PreviewKey> = emptySet(),
)
