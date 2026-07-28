package me.rosuh.easywatermark.ui.save

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.staggeredgrid.LazyVerticalStaggeredGrid
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridCells
import androidx.compose.foundation.lazy.staggeredgrid.items
import androidx.compose.foundation.rememberOverscrollEffect
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** Fixed card width for the Export waterfall (horizontal edge is constant). */
internal val ExportWaterfallCardWidth: Dp = 72.dp

/** Vertical spacing between waterfall cards / columns. */
internal val ExportWaterfallSpacing: Dp = 8.dp

/**
 * Bounded list viewport so format/quality controls and the primary CTA stay reachable
 * on a phone when the batch is large.
 */
internal val ExportWaterfallMaxHeight: Dp = 280.dp

/** Tallest card: width/height = 1/4. */
internal const val ExportAspectRatioMin: Float = 0.25f

/** Widest card: width/height = 4. */
internal const val ExportAspectRatioMax: Float = 4f

/**
 * Clamp positive pixel dimensions into a Compose aspectRatio value (width / height).
 * Invalid / non-positive → null (unknown — caller keeps prior freeze or square).
 *
 * Prefer already-decoded thumbnail or source metadata — never wait for export mutation of
 * [me.rosuh.easywatermark.data.model.ImageInfo.width]/height] (those stay 1×1 until save).
 */
internal fun exportCardAspectRatioOrNull(width: Int, height: Int): Float? {
    if (width <= 0 || height <= 0) return null
    // ImageInfo defaults are 1×1 — treat as unknown so thumbs can supply the real ratio.
    if (width == 1 && height == 1) return null
    val raw = width.toFloat() / height.toFloat()
    if (!raw.isFinite() || raw <= 0f) return null
    return raw.coerceIn(ExportAspectRatioMin, ExportAspectRatioMax)
}

/**
 * Resolve a layout ratio from optional primary dims then fallback (e.g. ImageInfo then thumb).
 * Returns null only when both are unknown.
 */
internal fun resolveExportCardAspectRatio(
    primaryWidth: Int,
    primaryHeight: Int,
    fallbackWidth: Int = 0,
    fallbackHeight: Int = 0,
): Float? =
    exportCardAspectRatioOrNull(primaryWidth, primaryHeight)
        ?: exportCardAspectRatioOrNull(fallbackWidth, fallbackHeight)

/**
 * Freeze-first-known aspect ratio. Once [key] records a value it never changes — keeps the
 * waterfall stable across Ready → Ing → Success even if ImageInfo.width/height mutate later.
 */
internal fun freezeExportAspectRatio(
    frozen: MutableMap<Any, Float>,
    key: Any,
    candidate: Float?,
): Float {
    frozen[key]?.let { return it }
    if (candidate != null && candidate.isFinite() && candidate > 0f) {
        val clamped = candidate.coerceIn(ExportAspectRatioMin, ExportAspectRatioMax)
        frozen[key] = clamped
        return clamped
    }
    return 1f
}

/**
 * Shared CMP shell for the save/export preview list.
 *
 * Vertical fixed-width waterfall below status/options. Hosts supply per-item thumbnails
 * (decode by source path/URI — never a single shared preview). Prefer a stable [itemKey]
 * so recycle does not reuse wrong cells. Hosts **must** decode thumbs off the main thread;
 * sync decode inside [thumbnail] freezes fling.
 *
 * @param itemAspectRatio optional width/height for each card. Return null while unknown;
 *   the first non-null value per [itemKey] is frozen for the sheet lifetime so export-time
 *   ImageInfo dimension mutation cannot reshuffle the waterfall.
 */
@Composable
fun <T> SaveExportPreviewBox(
    items: List<T>,
    emptyText: String,
    modifier: Modifier = Modifier,
    itemKey: ((T) -> Any)? = null,
    itemAspectRatio: (T) -> Float? = { null },
    thumbnail: @Composable (item: T, modifier: Modifier) -> Unit,
) {
    // Sheet-scoped freeze map — first known ratio wins per identity.
    val frozenRatios = remember { mutableMapOf<Any, Float>() }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(top = 16.dp)
            .heightIn(min = 96.dp, max = ExportWaterfallMaxHeight)
            .border(
                width = 1.dp,
                color = MaterialTheme.colorScheme.outline.copy(alpha = 0.35f),
                shape = RectangleShape,
            )
            .padding(horizontal = 5.dp)
            .testTag("sharedComposeExportPreviewBox"),
        contentAlignment = Alignment.Center,
    ) {
        if (items.isEmpty()) {
            Text(
                text = emptyText,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            val spacing = ExportWaterfallSpacing
            LazyVerticalStaggeredGrid(
                columns = StaggeredGridCells.FixedSize(ExportWaterfallCardWidth),
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = ExportWaterfallMaxHeight)
                    .testTag("sharedComposeExportWaterfall"),
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 8.dp),
                verticalItemSpacing = spacing,
                horizontalArrangement = Arrangement.spacedBy(spacing, Alignment.CenterHorizontally),
                overscrollEffect = rememberOverscrollEffect(),
            ) {
                if (itemKey != null) {
                    items(
                        items = items,
                        key = itemKey,
                        contentType = { _ -> "export_thumb" },
                    ) { item ->
                        val key = itemKey(item)
                        val ratio = freezeExportAspectRatio(
                            frozenRatios,
                            key,
                            itemAspectRatio(item),
                        )
                        thumbnail(
                            item,
                            Modifier
                                .fillMaxWidth()
                                .aspectRatio(ratio)
                                .testTag("sharedComposeExportThumb"),
                        )
                    }
                } else {
                    items(
                        items = items,
                        contentType = { _ -> "export_thumb" },
                    ) { item ->
                        // Index keys are unstable across list edits — hosts should pass itemKey.
                        val idx = items.indexOf(item)
                        val ratio = freezeExportAspectRatio(
                            frozenRatios,
                            idx,
                            itemAspectRatio(item),
                        )
                        thumbnail(
                            item,
                            Modifier
                                .fillMaxWidth()
                                .aspectRatio(ratio)
                                .testTag("sharedComposeExportThumb"),
                        )
                    }
                }
            }
        }
    }
}
