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
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
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
 * item pitch **56**.
 *
 * The highlight border is **fixed in the viewport center** and does not scroll with items.
 * Snap-fling settles a cell under that frame; a light haptic fires when the centered item changes.
 *
 * Tap selects once immediately; the scroll-to-center that follows is marked programmatic so the
 * settle handler does **not** fire a second [onImageSelected].
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

    fun applyCenteredSelection(target: ImageInfo, fromUser: Boolean) {
        val uri = target.uri.value
        if (uri == lastAppliedUri) return
        lastAppliedUri = uri
        if (fromUser) {
            composeHaptic.performHapticFeedback(HapticFeedbackType.LongPress)
            PlatformHaptics.selectionTick()
        }
        onImageSelected(target)
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
        if (images.isEmpty() || selectedUri == null) return@LaunchedEffect
        // Never fight an in-progress user fling.
        if (listState.isScrollInProgress && !programmaticScroll) return@LaunchedEffect
        val index = images.indexOfFirst { it.uri.value == selectedUri }
        if (index < 0) return@LaunchedEffect
        // Already tracking this URI and near center — skip (avoids re-scroll after batch stage).
        val atCenter = listState.firstVisibleItemIndex == index &&
            listState.firstVisibleItemScrollOffset == 0
        if (selectedUri == lastAppliedUri && atCenter) return@LaunchedEffect
        lastAppliedUri = selectedUri
        if (!atCenter) {
            scrollToIndexProgrammatic(index)
        }
    }

    // After *user* fling settles: select the cell under the fixed center frame + haptic.
    // Key only listState — do NOT restart collector when [images] grows mid-fling.
    LaunchedEffect(listState) {
        var sawUserScroll = false
        snapshotFlow { listState.isScrollInProgress to programmaticScroll }
            .distinctUntilChanged()
            .collect { (scrolling, programmatic) ->
                if (programmatic) {
                    return@collect
                }
                if (scrolling) {
                    sawUserScroll = true
                    return@collect
                }
                if (!sawUserScroll || images.isEmpty()) return@collect
                sawUserScroll = false

                val layout = listState.layoutInfo
                val idx = if (
                    listState.firstVisibleItemScrollOffset == 0 &&
                    listState.firstVisibleItemIndex in images.indices
                ) {
                    listState.firstVisibleItemIndex
                } else if (layout.visibleItemsInfo.isNotEmpty()) {
                    val viewportCenter =
                        (layout.viewportStartOffset + layout.viewportEndOffset) / 2
                    val closest = layout.visibleItemsInfo.minByOrNull { item ->
                        abs(item.offset + item.size / 2 - viewportCenter)
                    }
                    closest?.index?.coerceIn(images.indices) ?: return@collect
                } else {
                    return@collect
                }

                val target = images.getOrNull(idx) ?: return@collect
                applyCenteredSelection(target, fromUser = true)
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
                                programmaticScroll = true
                                coroutineScope.launch {
                                    try {
                                        listState.animateScrollToItem(index)
                                    } finally {
                                        programmaticScroll = false
                                    }
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
