package me.rosuh.easywatermark.ui

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import me.rosuh.easywatermark.shared.generated.resources.Res
import me.rosuh.easywatermark.shared.generated.resources.cd_checkbox
import me.rosuh.easywatermark.shared.generated.resources.cd_selected
import me.rosuh.easywatermark.shared.generated.resources.cd_unselected
import me.rosuh.easywatermark.ui.theme.DesignChipSelected
import me.rosuh.easywatermark.ui.theme.EwmTheme
import me.rosuh.easywatermark.ui.theme.MotionPolicy
import me.rosuh.easywatermark.ui.theme.currentMotionPolicy
import me.rosuh.easywatermark.ui.theme.motionDurationMs
import org.jetbrains.compose.resources.stringResource
import kotlin.math.max
import kotlin.math.min

/**
 * Shared CMP gallery grid/card shell.
 *
 * Selection is driven by [isSelected] / [onSetSelected] (local Snapshot state).
 * **Long-press + drag** (iOS Photos / legacy [MultiSelectRv]): after a long-press, sliding
 * Without lifting the finger paints a contiguous selection range from the anchor cell; reverse * drag shrinks the range. Near top/bottom edges the grid auto-scrolls while selecting.
 */
@Composable
fun GalleryImageGrid(
    images: List<Image>,
    checkIcon: Painter,
    isSelected: (Image) -> Boolean,
    onSetSelected: (Image, Int, Boolean) -> Unit,
    modifier: Modifier = Modifier,
    /**
     * I1: min cell size for [GridCells.Adaptive]. Default [GALLERY_ADAPTIVE_MIN_CELL_DP]
     * yields ~4 columns at 360dp and more on expanded widths (not Fixed(4) only).
     */
    minCellDp: Float = GALLERY_ADAPTIVE_MIN_CELL_DP,
    thumbnail: @Composable (image: Image, contentDescription: String, modifier: Modifier) -> Unit,
) {
    val selectedShape = remember { RoundedCornerShape(10.dp) }
    val gridState = rememberLazyGridState()
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    val edgePx = with(density) { 56.dp.toPx() }
    val autoScrollPx = with(density) { 28.dp.toPx() }

    // Gesture session: range paint from [dragAnchor] through [dragExtent].
    var dragActive by remember { mutableStateOf(false) }
    var dragAnchor by remember { mutableIntStateOf(-1) }
    var dragExtent by remember { mutableIntStateOf(-1) }
    // P3: freeze the list identity for the drag session so a host rebuild of [images]
    // mid-paint cannot re-key the grid or restart range arithmetic.
    var dragFrozenImages by remember { mutableStateOf<List<Image>?>(null) }
    val listForGesture = dragFrozenImages ?: images
    val displayImages = if (dragActive) listForGesture else images

    val imagesState = rememberUpdatedState(listForGesture)
    val onSetSelectedState = rememberUpdatedState(onSetSelected)

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
        // I1: adaptive min-cell — phone keeps ~4 cols; tablet/Desktop gain more.
        columns = GridCells.Adaptive(minSize = minCellDp.dp),
        modifier = modifier
            .pointerInput(displayImages.size) {
                detectDragGesturesAfterLongPress(
                    onDragStart = { offset ->
                        val list = imagesState.value
                        val idx = hitIndex(offset) ?: return@detectDragGesturesAfterLongPress
                        if (idx !in list.indices) return@detectDragGesturesAfterLongPress
                        dragFrozenImages = list
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
                        dragFrozenImages = null
                    },
                    onDragCancel = {
                        dragActive = false
                        dragAnchor = -1
                        dragExtent = -1
                        dragFrozenImages = null
                    },
                )
            },
        state = gridState,
        userScrollEnabled = !dragActive,
        horizontalArrangement = Arrangement.spacedBy(1.5.dp),
        verticalArrangement = Arrangement.spacedBy(1.5.dp),
    ) {
        itemsIndexed(
            items = displayImages,
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
    val selectedPhrase = stringResource(Res.string.cd_selected)
    val unselectedPhrase = stringResource(Res.string.cd_unselected)
    val cardCd = AccessibilitySemantics.galleryImageContentDescription(
        imageName = image.name,
        selected = selected,
        selectedPhrase = selectedPhrase,
        unselectedPhrase = unselectedPhrase,
    )
    // M1 + M10: animate 1→0.8 select scale (prod ObjectAnimator 200ms); spring under Full.
    val motionPolicy = currentMotionPolicy()
    val selectMs = motionDurationMs(motionPolicy, EwmTheme.motion.gallerySelectMs)
    val targetScale = if (selected) EwmTheme.motion.gallerySelectScale else 1f
    val selectScale by animateFloatAsState(
        targetValue = targetScale,
        animationSpec = when {
            selectMs <= 0 -> snap()
            motionPolicy == MotionPolicy.Full -> spring(
                dampingRatio = Spring.DampingRatioMediumBouncy,
                stiffness = Spring.StiffnessMediumLow,
            )
            else -> tween(durationMillis = selectMs)
        },
        label = "gallerySelectScale",
    )
    Box(
        modifier = modifier
            .fillMaxWidth()
            .aspectRatio(1f)
            .background(DesignChipSelected)
            // I2: name + selected + checkbox role (not color-only selection).
            .semantics {
                contentDescription = cardCd
                this.selected = selected
                stateDescription = if (selected) selectedPhrase else unselectedPhrase
                role = Role.Checkbox
            }
            .testTag("galleryImageCard")
            .clickable(onClick = onToggle),
    ) {
        // Fixed layout bounds for the thumb; selection scale is Draw-phase only (deferring-state-reads).
        Box(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    scaleX = selectScale
                    scaleY = selectScale
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
    val checkboxLabel = stringResource(Res.string.cd_checkbox)
    // Parent GalleryImageCard owns full selection CD; icon is decorative when selected.
    val iconCd = if (selected) {
        AccessibilitySemantics.checkboxContentDescription(checkboxLabel, selected = true)
    } else {
        null
    }
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
                contentDescription = iconCd,
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}
