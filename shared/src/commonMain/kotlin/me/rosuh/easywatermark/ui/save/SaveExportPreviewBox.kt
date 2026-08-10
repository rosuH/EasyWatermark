package me.rosuh.easywatermark.ui.save

import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberOverscrollEffect
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.coerceAtLeast
import androidx.compose.ui.unit.dp
import kotlin.math.abs

/**
 * Shared CMP shell for the save/export preview list.
 *
 * Hosts supply per-item thumbnails (decode by source path/URI — never a single shared preview).
 * Prefer a stable [itemKey] so LazyRow does not reuse wrong cells across identities.
 * Hosts **must** decode thumbs off the main thread (see filmstrip produceState pattern);
 * Sync decode inside [thumbnail] freezes fling.
 *
 * **Desktop scroll:** LazyRow only consumes horizontal scroll deltas by default, so a plain
 * mouse wheel (vertical) does nothing and click-drag often loses to [ModalBottomSheet]
 * sheet gestures. We map vertical wheel → horizontal pan and accept horizontal drag so the
 * export thumb strip is scrollable on Desktop trackpad/mouse (owner 2026-08-10).
 */
@Composable
fun <T> SaveExportPreviewBox(
    items: List<T>,
    emptyText: String,
    modifier: Modifier = Modifier,
    itemKey: ((T) -> Any)? = null,
    thumbnail: @Composable (item: T, modifier: Modifier) -> Unit,
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(top = 20.dp)
            // Slightly shorter than the old 145.dp so format/quality + CTA fit under a
            // wrap-height sheet with dimmed editor peek (production export chrome).
            .height(110.dp)
            .border(
                width = 1.dp,
                color = MaterialTheme.colorScheme.outline.copy(alpha = 0.35f),
                shape = RectangleShape,
            )
            .padding(horizontal = 5.dp)
            .testTag("sharedComposeExportPreviewList"),
        contentAlignment = Alignment.Center,
    ) {
        if (items.isEmpty()) {
            Text(
                text = emptyText,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            val density = LocalDensity.current
            val listState = rememberLazyListState()
            var rowWidth by remember { mutableStateOf(0.dp) }
            // 72dp is enough for export status chrome; lighter than 96dp for multi-image fling.
            val itemSize = 72.dp
            val spacing = 8.dp
            val minPad = 8.dp
            val contentWidth =
                itemSize * items.size + spacing * (items.size - 1).coerceAtLeast(0)
            // Horizontally center the thumbnail group when it is narrower than the box
            // (single-image export list was flush-left before this).
            val sidePad = ((rowWidth - contentWidth) / 2).coerceAtLeast(minPad)

            LazyRow(
                state = listState,
                userScrollEnabled = true,
                modifier = Modifier
                    .fillMaxSize()
                    .onGloballyPositioned {
                        rowWidth = with(density) { it.size.width.toDp() }
                    }
                    // Vertical mouse wheel / trackpad → horizontal pan (Desktop).
                    .pointerInput(listState) {
                        awaitPointerEventScope {
                            while (true) {
                                val event = awaitPointerEvent(PointerEventPass.Main)
                                if (event.type != PointerEventType.Scroll) continue
                                val scroll = event.changes.firstOrNull()?.scrollDelta ?: continue
                                // Prefer true horizontal delta; else map vertical wheel.
                                val delta = when {
                                    abs(scroll.x) > abs(scroll.y) -> scroll.x
                                    abs(scroll.y) > 0f -> scroll.y
                                    else -> 0f
                                }
                                if (delta == 0f) continue
                                val can = if (delta > 0f) {
                                    listState.canScrollForward
                                } else {
                                    listState.canScrollBackward
                                }
                                if (!can) continue
                                listState.dispatchRawDelta(delta)
                                event.changes.forEach { it.consume() }
                            }
                        }
                    }
                    // Mouse click-drag pan (Desktop). Touch/Stylus downs are ignored so LazyRow
                    // keeps its native fling path on Android.
                    .pointerInput(listState) {
                        awaitEachGesture {
                            val down = awaitFirstDown(requireUnconsumed = false)
                            if (down.type != PointerType.Mouse) return@awaitEachGesture
                            drag(down.id) { change ->
                                val dx = change.positionChange().x
                                if (dx == 0f) return@drag
                                val can = if (dx < 0f) {
                                    listState.canScrollForward
                                } else {
                                    listState.canScrollBackward
                                }
                                if (!can) return@drag
                                listState.dispatchRawDelta(-dx)
                                change.consume()
                            }
                        }
                    },
                contentPadding = PaddingValues(horizontal = sidePad, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(spacing),
                verticalAlignment = Alignment.CenterVertically,
                overscrollEffect = rememberOverscrollEffect(),
            ) {
                if (itemKey != null) {
                    items(
                        items = items,
                        key = itemKey,
                        contentType = { _ -> "export_thumb" },
                    ) { item ->
                        thumbnail(item, Modifier.size(itemSize))
                    }
                } else {
                    items(
                        items = items,
                        contentType = { _ -> "export_thumb" },
                    ) { item ->
                        thumbnail(item, Modifier.size(itemSize))
                    }
                }
            }
        }
    }
}
