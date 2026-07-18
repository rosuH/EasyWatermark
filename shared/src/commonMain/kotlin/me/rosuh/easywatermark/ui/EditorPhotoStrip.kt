package me.rosuh.easywatermark.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.snapping.rememberSnapFlingBehavior
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberOverscrollEffect
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.coerceAtLeast
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import me.rosuh.easywatermark.data.model.ImageInfo
import kotlin.math.abs

/**
 * Design filmstrip: content **40×40**, fixed center frame **48×48** (brand stroke **1.5**, **r=2**),
 * Item pitch **56**. *
 * The highlight border is **fixed in the viewport center** and does not scroll with items.
 * Snap-fling settles a cell under that frame; a light haptic fires when the centered item changes.
 *
 * Tap selects once immediately; the scroll-to-center that follows is marked programmatic so the
 * settle handler does **not** fire a second [onImageSelected].
 *
 * User fling/drag: selection updates only after scroll settles, using the item closest to the
 * **viewport center** (not firstVisibleItemIndex — that is wrong with center contentPadding).
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun EditorPhotoStrip(
    images: List<ImageInfo>,
    selectedImage: ImageInfo?,
    modifier: Modifier = Modifier,
    onImageSelected: (ImageInfo) -> Unit = {},
    thumbnail: @Composable (imageInfo: ImageInfo, contentDescription: String, modifier: Modifier) -> Unit,
) {
    val listState = rememberLazyListState()
    val overscroll = rememberOverscrollEffect()
    val snapFling = rememberSnapFlingBehavior(lazyListState = listState)
    val coroutineScope = rememberCoroutineScope()
    val composeHaptic = LocalHapticFeedback.current
    var stripWidth by remember { mutableStateOf(0.dp) }
    val cellSize = 48.dp
    val density = LocalDensity.current
    val selectedUri = selectedImage?.uri?.value
    val frameShape = RoundedCornerShape(2.dp)
    // Own last-applied URI (not a stale composition capture of selectedUri).
    var lastAppliedUri by remember { mutableStateOf(selectedUri) }
    // True while we animateScrollToItem from tap / external selection — skip settle select.
    var programmaticScroll by remember { mutableStateOf(false) }

    val imagesState = rememberUpdatedState(images)
    val onImageSelectedState = rememberUpdatedState(onImageSelected)
    val lastAppliedState = rememberUpdatedState(lastAppliedUri)

    fun applyCenteredSelection(target: ImageInfo, fromUser: Boolean) {
        val uri = target.uri.value
        if (uri == lastAppliedUri) return
        lastAppliedUri = uri
        if (fromUser) {
            composeHaptic.performHapticFeedback(HapticFeedbackType.LongPress)
            PlatformHaptics.selectionTick()
        }
        onImageSelectedState.value(target)
    }

    suspend fun scrollToIndexProgrammatic(index: Int) {
        // Mark *before* animate so isScrollInProgress=true never counts as user fling.
        programmaticScroll = true
        try {
            listState.animateScrollToItem(index)
        } finally {
            programmaticScroll = false
        }
    }

    // Re-center only when selection URI changes (external select / settle).
    // Do NOT key on images.size — list growth must not yank scroll (pick batch / add-more).
    LaunchedEffect(selectedUri) {
        val imgs = imagesState.value
        if (imgs.isEmpty() || selectedUri == null) return@LaunchedEffect
        // Never fight an in-progress user fling.
        if (listState.isScrollInProgress && !programmaticScroll) return@LaunchedEffect
        val index = imgs.indexOfFirst { it.uri.value == selectedUri }
        if (index < 0) return@LaunchedEffect
        val centerIdx = centeredItemIndex(listState)
        val atCenter = centerIdx != null &&
            imgs.getOrNull(centerIdx)?.uri?.value == selectedUri
        if (selectedUri == lastAppliedUri && atCenter) return@LaunchedEffect
        // Keep lastApplied in sync with parent selection even if we only re-center.
        lastAppliedUri = selectedUri
        if (!atCenter) {
            scrollToIndexProgrammatic(index)
        }
    }

    // After *user* scroll/fling settles: select the cell under the fixed center frame.
    LaunchedEffect(listState) {
        var wasUserScrolling = false
        snapshotFlow {
            listState.isScrollInProgress to programmaticScroll
        }
            .distinctUntilChanged()
            .collect { (scrolling, programmatic) ->
                if (programmatic) {
                    // Programmatic re-center must not look like a user fling settle.
                    wasUserScrolling = false
                    return@collect
                }
                if (scrolling) {
                    wasUserScrolling = true
                    return@collect
                }
                if (!wasUserScrolling) return@collect
                wasUserScrolling = false

                // Wait 2 frames so snap fling layout + item offsets are committed.
                withFrameNanos { }
                withFrameNanos { }

                val imgs = imagesState.value
                if (imgs.isEmpty()) return@collect
                val idx = centeredItemIndex(listState) ?: return@collect
                val target = imgs.getOrNull(idx) ?: return@collect
                val uri = target.uri.value
                if (uri == lastAppliedState.value) return@collect
                lastAppliedUri = uri
                composeHaptic.performHapticFeedback(HapticFeedbackType.LongPress)
                PlatformHaptics.selectionTick()
                onImageSelectedState.value(target)
            }
    }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(56.dp)
            .onGloballyPositioned {
                stripWidth = with(density) { it.size.width.toDp() }
            },
        contentAlignment = Alignment.Center,
    ) {
        LazyRow(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = (stripWidth - cellSize).coerceAtLeast(0.dp) / 2,
                end = (stripWidth - cellSize).coerceAtLeast(0.dp) / 2,
            ),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            state = listState,
            flingBehavior = snapFling,
            overscrollEffect = overscroll,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            itemsIndexed(
                items = images,
                key = { _, image -> image.uri.value },
                contentType = { _, _ -> "filmstrip_thumb" },
            ) { index, imageInfo ->
                Box(
                    modifier = Modifier.size(cellSize),
                    contentAlignment = Alignment.Center,
                ) {
                    thumbnail(
                        imageInfo,
                        "image",
                        Modifier
                            .size(40.dp)
                            .clip(frameShape)
                            .clickable {
                                // Select once; mark programmatic *before* scroll so settle is ignored.
                                applyCenteredSelection(imageInfo, fromUser = true)
                                coroutineScope.launch {
                                    scrollToIndexProgrammatic(index)
                                }
                            },
                    )
                }
            }
        }

        // Fixed center selection frame (does not scroll with items).
        Box(
            modifier = Modifier
                .size(cellSize)
                .align(Alignment.Center)
                .border(
                    width = 1.5.dp,
                    color = MaterialTheme.colorScheme.primary,
                    shape = frameShape,
                ),
        )
    }
}

/**
 * Index of the visible item whose center is closest to the viewport center.
 * Correct with large start/end [contentPadding] (center-aligned filmstrip); [LazyListState.firstVisibleItemIndex]
 * Alone points at the *leading* cell, not the one under the fixed center frame. */
private fun centeredItemIndex(listState: LazyListState): Int? {
    val layout = listState.layoutInfo
    val visible = layout.visibleItemsInfo
    if (visible.isEmpty()) return null
    val viewportCenter = (layout.viewportStartOffset + layout.viewportEndOffset) / 2
    return visible.minByOrNull { item ->
        abs(item.offset + item.size / 2 - viewportCenter)
    }?.index
}
