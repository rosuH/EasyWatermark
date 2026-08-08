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
import me.rosuh.easywatermark.data.model.ImageInfoUi
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.coerceAtLeast
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlin.math.abs

/**
 * Design filmstrip metrics. Single source for legacy and progressive paths.
 *
 * Content **40×40**, fixed center frame **48×48** (brand stroke **1.5**, **r=2**),
 * item pitch **56** (48 cell + 8 gap), rail height **56**.
 */
internal object EditorFilmstripMetrics {
    val RailHeight: Dp = 56.dp
    val CellSize: Dp = 48.dp
    val ContentSize: Dp = 40.dp
    val ItemGap: Dp = 8.dp
    val FrameBorder: Dp = 1.5.dp
    val FrameRadius: Dp = 2.dp
    val Pitch: Dp = CellSize + ItemGap
}

/**
 * Production-used interaction decisions for the filmstrip scaffold.
 * Scaffold branches call these so unit tests exercise the real gate, not dead helpers.
 */
internal object EditorFilmstripInteraction {
    /** Tap publishes once when the cell is selectable and not already applied. */
    fun shouldPublishOnTap(
        canSelect: Boolean,
        itemKey: String,
        lastAppliedKey: String?,
    ): Boolean = canSelect && itemKey != lastAppliedKey

    /**
     * User fling/drag settle publishes only after real user scroll ends on a selectable
     * center cell whose key differs from the last applied selection.
     */
    fun shouldPublishOnSettle(
        wasUserScrolling: Boolean,
        programmatic: Boolean,
        canSelect: Boolean,
        centeredKey: String?,
        lastAppliedKey: String?,
    ): Boolean =
        wasUserScrolling &&
            !programmatic &&
            canSelect &&
            centeredKey != null &&
            centeredKey != lastAppliedKey

    /**
     * Programmatic re-center runs when selection key is off-center and the user is not
     * mid-fling. Mere list append (same selectedKey) never requests a recenter.
     */
    fun shouldProgrammaticRecenter(
        selectedKey: String?,
        lastAppliedKey: String?,
        atCenter: Boolean,
        userScrollInProgress: Boolean,
    ): Boolean {
        if (selectedKey == null) return false
        if (userScrollInProgress) return false
        if (selectedKey == lastAppliedKey && atCenter) return false
        return !atCenter
    }

    /** Effect key is selection only — list size must not yank scroll on append. */
    fun recenterEffectKey(selectedKey: String?): String? = selectedKey

    /** Progressive slots: only Ready may become a Session selection. */
    fun canSelectSlot(slot: EditorMediaSlot): Boolean = slot is EditorMediaSlot.Ready
}

/**
 * Design filmstrip: content **40×40**, fixed center frame **48×48** (brand stroke **1.5**, **r=2**),
 * Item pitch **56**.
 *
 * The highlight border is **fixed in the viewport center** and does not scroll with items.
 * Snap-fling settles a cell under that frame; a light haptic fires when the centered item changes.
 *
 * Tap selects once immediately; the scroll-to-center that follows is marked programmatic so the
 * settle handler does **not** fire a second [onImageSelected].
 *
 * User fling/drag: selection updates only after scroll settles, using the item closest to the
 * **viewport center** (not firstVisibleItemIndex — that is wrong with center contentPadding).
 */
@Composable
fun EditorPhotoStrip(
    images: List<ImageInfoUi>,
    selectedImage: ImageInfoUi?,
    modifier: Modifier = Modifier,
    onImageSelected: (ImageInfoUi) -> Unit = {},
    thumbnail: @Composable (imageInfo: ImageInfoUi, contentDescription: String, modifier: Modifier) -> Unit,
) {
    val frameShape = RoundedCornerShape(EditorFilmstripMetrics.FrameRadius)
    EditorFilmstripScaffold(
        items = images,
        keyOf = { it.uri.value },
        selectedKey = selectedImage?.uri?.value,
        canSelect = { true },
        onItemSelected = onImageSelected,
        modifier = modifier,
        testTag = "editorPhotoStrip",
        itemContent = { imageInfo, contentModifier ->
            thumbnail(
                imageInfo,
                "image",
                contentModifier
                    .size(EditorFilmstripMetrics.ContentSize)
                    .clip(frameShape),
            )
        },
    )
}

/**
 * Single filmstrip scaffold owned by the legacy geometry/interaction contract.
 * Progressive slots render through [itemContent] inside the same LazyRow.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun <T> EditorFilmstripScaffold(
    items: List<T>,
    keyOf: (T) -> String,
    selectedKey: String?,
    canSelect: (T) -> Boolean,
    onItemSelected: (T) -> Unit,
    modifier: Modifier = Modifier,
    testTag: String = "editorFilmstripScaffold",
    itemContent: @Composable (item: T, contentModifier: Modifier) -> Unit,
) {
    val listState = rememberLazyListState()
    val overscroll = rememberOverscrollEffect()
    val snapFling = rememberSnapFlingBehavior(lazyListState = listState)
    val coroutineScope = rememberCoroutineScope()
    val composeHaptic = LocalHapticFeedback.current
    var stripWidth by remember { mutableStateOf(0.dp) }
    val cellSize = EditorFilmstripMetrics.CellSize
    val density = LocalDensity.current
    val frameShape = RoundedCornerShape(EditorFilmstripMetrics.FrameRadius)
    // Own last-applied key (not a stale composition capture of selectedKey).
    var lastAppliedKey by remember { mutableStateOf(selectedKey) }
    // True while we animateScrollToItem from tap / external selection — skip settle select.
    var programmaticScroll by remember { mutableStateOf(false) }

    val itemsState = rememberUpdatedState(items)
    val keyOfState = rememberUpdatedState(keyOf)
    val canSelectState = rememberUpdatedState(canSelect)
    val onItemSelectedState = rememberUpdatedState(onItemSelected)
    val lastAppliedState = rememberUpdatedState(lastAppliedKey)

    fun applyCenteredSelection(target: T, fromUser: Boolean) {
        val key = keyOfState.value(target)
        if (!EditorFilmstripInteraction.shouldPublishOnTap(
                canSelect = canSelectState.value(target),
                itemKey = key,
                lastAppliedKey = lastAppliedKey,
            )
        ) {
            return
        }
        lastAppliedKey = key
        if (fromUser) {
            composeHaptic.performHapticFeedback(HapticFeedbackType.LongPress)
            PlatformHaptics.selectionTick()
        }
        onItemSelectedState.value(target)
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

    // Re-center only when selection key changes (external select / settle).
    // Do NOT key on items.size — list growth must not yank scroll (pick batch / add-more).
    LaunchedEffect(EditorFilmstripInteraction.recenterEffectKey(selectedKey)) {
        val current = itemsState.value
        if (current.isEmpty() || selectedKey == null) return@LaunchedEffect
        val index = current.indexOfFirst { keyOfState.value(it) == selectedKey }
        if (index < 0) return@LaunchedEffect
        val centerIdx = centeredItemIndex(listState)
        val atCenter = centerIdx != null &&
            current.getOrNull(centerIdx)?.let { keyOfState.value(it) } == selectedKey
        val userScrollInProgress = listState.isScrollInProgress && !programmaticScroll
        val priorApplied = lastAppliedKey
        val needsRecenter = EditorFilmstripInteraction.shouldProgrammaticRecenter(
            selectedKey = selectedKey,
            lastAppliedKey = priorApplied,
            atCenter = atCenter,
            userScrollInProgress = userScrollInProgress,
        )
        // Keep lastApplied in sync with parent selection even if we only re-center.
        lastAppliedKey = selectedKey
        if (needsRecenter) {
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

                val current = itemsState.value
                if (current.isEmpty()) return@collect
                val idx = centeredItemIndex(listState) ?: return@collect
                val target = current.getOrNull(idx) ?: return@collect
                val key = keyOfState.value(target)
                if (!EditorFilmstripInteraction.shouldPublishOnSettle(
                        wasUserScrolling = true,
                        programmatic = false,
                        canSelect = canSelectState.value(target),
                        centeredKey = key,
                        lastAppliedKey = lastAppliedState.value,
                    )
                ) {
                    return@collect
                }
                lastAppliedKey = key
                composeHaptic.performHapticFeedback(HapticFeedbackType.LongPress)
                PlatformHaptics.selectionTick()
                onItemSelectedState.value(target)
            }
    }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(EditorFilmstripMetrics.RailHeight)
            .onGloballyPositioned {
                stripWidth = with(density) { it.size.width.toDp() }
            }
            .testTag(testTag),
        contentAlignment = Alignment.Center,
    ) {
        LazyRow(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = (stripWidth - cellSize).coerceAtLeast(0.dp) / 2,
                end = (stripWidth - cellSize).coerceAtLeast(0.dp) / 2,
            ),
            horizontalArrangement = Arrangement.spacedBy(EditorFilmstripMetrics.ItemGap),
            state = listState,
            flingBehavior = snapFling,
            overscrollEffect = overscroll,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            itemsIndexed(
                items = items,
                key = { _, item -> keyOf(item) },
                // Distinguish Ready vs Pending/Failed for Lazy composition reuse during import churn.
                contentType = { _, item ->
                    when (item) {
                        is EditorMediaSlot.Ready -> "filmstrip_ready"
                        is EditorMediaSlot.Pending -> "filmstrip_pending"
                        is EditorMediaSlot.Failed -> "filmstrip_failed"
                        else -> "filmstrip_cell"
                    }
                },
            ) { index, item ->
                Box(
                    modifier = Modifier.size(cellSize),
                    contentAlignment = Alignment.Center,
                ) {
                    // Legacy path: plain clickable. Progressive secondary actions live in itemContent.
                    val interactionModifier = if (canSelect(item)) {
                        Modifier.clickable {
                            // Select once; mark programmatic *before* scroll so settle is ignored.
                            applyCenteredSelection(item, fromUser = true)
                            coroutineScope.launch {
                                scrollToIndexProgrammatic(index)
                            }
                        }
                    } else {
                        Modifier
                    }
                    itemContent(item, interactionModifier)
                }
            }
        }

        // Fixed center selection frame (does not scroll with items).
        Box(
            modifier = Modifier
                .size(cellSize)
                .align(Alignment.Center)
                .border(
                    width = EditorFilmstripMetrics.FrameBorder,
                    color = MaterialTheme.colorScheme.primary,
                    shape = frameShape,
                ),
        )
    }
}

/**
 * Index of the visible item whose center is closest to the viewport center.
 * Correct with large start/end [contentPadding] (center-aligned filmstrip); [LazyListState.firstVisibleItemIndex]
 * alone points at the *leading* cell, not the one under the fixed center frame.
 */
internal fun centeredItemIndex(listState: LazyListState): Int? {
    val layout = listState.layoutInfo
    val visible = layout.visibleItemsInfo
    if (visible.isEmpty()) return null
    val viewportCenter = (layout.viewportStartOffset + layout.viewportEndOffset) / 2
    return visible.minByOrNull { item ->
        abs(item.offset + item.size / 2 - viewportCenter)
    }?.index
}
