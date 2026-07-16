package me.rosuh.easywatermark.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image as FoundationImage
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import me.rosuh.easywatermark.ui.theme.DesignChipSelected
import kotlin.math.max
import kotlin.math.min

/**
 * Shared CMP gallery grid/card shell.
 *
 * Selection is driven by [isSelected] / [onSetSelected] (local Snapshot state).
 * **Long-press + drag** (iOS Photos / legacy [MultiSelectRv]): after a long-press, sliding
 * without lifting the finger paints a contiguous selection range from the anchor cell; reverse
 * drag shrinks the range. Near top/bottom edges the grid auto-scrolls while selecting.
 */
@Composable
fun GalleryImageGrid(
    images: List<Image>,
    checkIcon: Painter,
    isSelected: (Image) -> Boolean,
    onSetSelected: (Image, Int, Boolean) -> Unit,
    modifier: Modifier = Modifier,
    thumbnail: @Composable (image: Image, contentDescription: String, modifier: Modifier) -> Unit,
) {
    val selectedShape = remember { RoundedCornerShape(10.dp) }
    val gridState = rememberLazyGridState()
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    val edgePx = with(density) { 56.dp.toPx() }
    val autoScrollPx = with(density) { 28.dp.toPx() }

    val imagesState = rememberUpdatedState(images)
    val onSetSelectedState = rememberUpdatedState(onSetSelected)

    // Gesture session: range paint from [dragAnchor] through [dragExtent].
    var dragActive by remember { mutableStateOf(false) }
    var dragAnchor by remember { mutableIntStateOf(-1) }
    var dragExtent by remember { mutableIntStateOf(-1) }

    fun hitIndex(pos: Offset): Int? {
        val info = gridState.layoutInfo
        val x = pos.x
        val y = pos.y
        val hit = info.visibleItemsInfo.firstOrNull { item ->
            val left = item.offset.x.toFloat()
            val top = item.offset.y.toFloat()
            val right = left + item.size.width
            val bottom = top + item.size.height
            x >= left && x < right && y >= top && y < bottom
        }
        return hit?.index
    }

    fun applyRange(from: Int, to: Int, list: List<Image>) {
        if (list.isEmpty()) return
        val lo = min(from, to).coerceIn(0, list.lastIndex)
        val hi = max(from, to).coerceIn(0, list.lastIndex)
        val prevLo = min(dragAnchor, dragExtent).coerceIn(0, list.lastIndex)
        val prevHi = max(dragAnchor, dragExtent).coerceIn(0, list.lastIndex)
        // Deselect cells that left the painted range.
        for (i in prevLo..prevHi) {
            if (i < lo || i > hi) {
                onSetSelectedState.value(list[i], i, false)
            }
        }
        // Select full new range (anchor → finger).
        for (i in lo..hi) {
            onSetSelectedState.value(list[i], i, true)
        }
        dragExtent = to.coerceIn(0, list.lastIndex)
    }

    fun maybeAutoScroll(y: Float, viewportHeight: Float) {
        val delta = when {
            y < edgePx -> -autoScrollPx
            y > viewportHeight - edgePx -> autoScrollPx
            else -> 0f
        }
        if (delta != 0f) {
            scope.launch {
                gridState.scrollBy(delta)
            }
        }
    }

    LazyVerticalGrid(
        columns = GridCells.Fixed(4),
        modifier = modifier
            .pointerInput(images.size) {
                detectDragGesturesAfterLongPress(
                    onDragStart = { offset ->
                        val list = imagesState.value
                        val idx = hitIndex(offset) ?: return@detectDragGesturesAfterLongPress
                        if (idx !in list.indices) return@detectDragGesturesAfterLongPress
                        dragActive = true
                        dragAnchor = idx
                        dragExtent = idx
                        onSetSelectedState.value(list[idx], idx, true)
                        PlatformHaptics.selectionTick()
                    },
                    onDrag = { change, _ ->
                        change.consume()
                        if (!dragActive) return@detectDragGesturesAfterLongPress
                        val list = imagesState.value
                        if (list.isEmpty()) return@detectDragGesturesAfterLongPress
                        val idx = hitIndex(change.position)
                        if (idx != null && idx in list.indices && idx != dragExtent) {
                            applyRange(dragAnchor, idx, list)
                        }
                        maybeAutoScroll(
                            y = change.position.y,
                            viewportHeight = size.height.toFloat(),
                        )
                    },
                    onDragEnd = {
                        dragActive = false
                        dragAnchor = -1
                        dragExtent = -1
                    },
                    onDragCancel = {
                        dragActive = false
                        dragAnchor = -1
                        dragExtent = -1
                    },
                )
            },
        state = gridState,
        userScrollEnabled = !dragActive,
        horizontalArrangement = Arrangement.spacedBy(1.5.dp),
        verticalArrangement = Arrangement.spacedBy(1.5.dp),
    ) {
        itemsIndexed(
            items = images,
            key = { _: Int, item: Image -> item.id },
            contentType = { _, _ -> "gallery_cell" },
        ) { index, image ->
            val selected = isSelected(image)
            GalleryImageCard(
                image = image,
                selected = selected,
                checkIcon = checkIcon,
                selectedShape = selectedShape,
                onToggle = {
                    onSetSelected(image, index, !selected)
                },
                thumbnail = thumbnail,
            )
        }
    }
}

@Composable
private fun GalleryImageCard(
    image: Image,
    selected: Boolean,
    checkIcon: Painter,
    selectedShape: RoundedCornerShape,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
    thumbnail: @Composable (image: Image, contentDescription: String, modifier: Modifier) -> Unit,
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .aspectRatio(1f)
            .background(DesignChipSelected)
            .clickable(onClick = onToggle),
    ) {
        // Fixed layout bounds for the thumb; selection is Draw-phase scale only.
        Box(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    val s = if (selected) 0.8f else 1f
                    scaleX = s
                    scaleY = s
                }
                .then(
                    if (selected) {
                        Modifier.clip(selectedShape)
                    } else {
                        Modifier
                    },
                ),
        ) {
            thumbnail(
                image,
                image.name,
                Modifier.fillMaxSize(),
            )
        }

        CircleCheckBox(
            selected = selected,
            checkIcon = checkIcon,
            onClick = onToggle,
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(6.dp)
                .size(19.dp),
        )
    }
}

@Composable
private fun CircleCheckBox(
    selected: Boolean,
    checkIcon: Painter,
    modifier: Modifier = Modifier,
    onClick: () -> Unit = {},
) {
    val color = MaterialTheme.colorScheme
    val unselectedBorder = remember(color.onSurface) {
        BorderStroke(2.dp, color.onSurface.copy(alpha = 0.6f))
    }
    val interactionSource = remember { MutableInteractionSource() }
    val boxModifier = if (selected) {
        modifier.background(color.secondary, shape = CircleShape)
    } else {
        modifier.border(unselectedBorder, shape = CircleShape)
    }
    Box(
        modifier = boxModifier
            .clickable(
                onClick = onClick,
                indication = null,
                interactionSource = interactionSource,
            ),
        contentAlignment = Alignment.Center,
    ) {
        if (selected) {
            FoundationImage(
                painter = checkIcon,
                contentDescription = "check box",
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}
