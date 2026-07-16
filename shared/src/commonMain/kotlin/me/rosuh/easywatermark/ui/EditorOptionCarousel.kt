package me.rosuh.easywatermark.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.coerceAtLeast
import androidx.compose.ui.unit.dp
import me.rosuh.easywatermark.ui.theme.DesignChipSelected

/**
 * Design: function chips **72×56**, selected fill `#2C2C14`, corner **r=2**.
 *
 * No [Modifier.animateItem]: Content/Style/Layout catalogs are unrelated lists. Index-keyed
 * insert/placement animation made chips overlap while flinging right after a tab switch
 * (labels stacking as "StyColor" / "ColAlpha"). Host should also [key] this composable by tab.
 */
@Composable
fun <T> EditorOptionCarousel(
    options: List<T>,
    useCompactPadding: Boolean,
    onOptionSelected: (T) -> Unit,
    modifier: Modifier = Modifier,
    selectedOption: T? = null,
    /**
     * Stable identity for LazyRow items (e.g. [me.rosuh.easywatermark.data.model.FuncType]).
     * Falls back to index only when omitted.
     */
    itemKey: ((T) -> Any)? = null,
    itemContent: @Composable (option: T, selected: Boolean) -> Unit,
) {
    var optionWidth by remember { mutableStateOf(0.dp) }
    val density = LocalDensity.current
    val listState = rememberLazyListState()
    val overscroll = rememberOverscrollEffect()
    // Figma button instance width; chips sit flush (no spacedBy).
    val itemWidth = 72.dp
    val groupWidth = itemWidth * options.size.coerceAtLeast(0)
    // When the chip group fits (phone landscape / Desktop), center it.
    // When it overflows, use compact edge pad (scrollable) or single-item center pad.
    val contentPadding = when {
        optionWidth > 0.dp && groupWidth > 0.dp && optionWidth >= groupWidth -> {
            (optionWidth - groupWidth) / 2
        }
        useCompactPadding -> 8.dp
        else -> (optionWidth - itemWidth).coerceAtLeast(0.dp) / 2
    }

    val selectedIndex = remember(options, selectedOption) {
        selectedOption?.let { options.indexOf(it) }?.takeIf { it >= 0 } ?: -1
    }
    // Instant scroll — do not animateScroll (fights user fling after tab change).
    LaunchedEffect(selectedIndex) {
        if (selectedIndex in options.indices && options.isNotEmpty()) {
            runCatching { listState.scrollToItem(selectedIndex) }
        }
    }

    LazyRow(
        modifier
            .fillMaxWidth()
            // Design option row height = 56
            .height(56.dp)
            .clipToBounds()
            .onGloballyPositioned {
                optionWidth = with(density) { it.size.width.toDp() }
            },
        state = listState,
        overscrollEffect = overscroll,
        contentPadding = PaddingValues(start = contentPadding, end = contentPadding),
        horizontalArrangement = Arrangement.spacedBy(0.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        itemsIndexed(
            items = options,
            key = { index, item -> itemKey?.invoke(item) ?: index },
            contentType = { _, _ -> "editor_option_chip" },
        ) { _, item ->
            val isSelected = selectedOption != null && selectedOption == item
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .width(itemWidth)
                    .fillMaxHeight()
                    .clip(RoundedCornerShape(2.dp))
                    .background(
                        if (isSelected) {
                            MaterialTheme.colorScheme.surfaceVariant.takeIf {
                                it != Color.Unspecified
                            } ?: DesignChipSelected
                        } else {
                            Color.Transparent
                        },
                    )
                    .clickable { onOptionSelected(item) },
            ) {
                Column(
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clipToBounds(),
                ) {
                    itemContent(item, isSelected)
                }
            }
        }
    }
}
