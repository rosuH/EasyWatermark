package me.rosuh.easywatermark.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.coerceAtLeast
import androidx.compose.ui.unit.dp

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun <T> EditorOptionCarousel(
    options: List<T>,
    useCompactPadding: Boolean,
    onOptionSelected: (T) -> Unit,
    modifier: Modifier = Modifier,
    itemContent: @Composable (T) -> Unit,
) {
    var optionWidth by remember {
        mutableStateOf(0.dp)
    }

    val density = LocalDensity.current
    val listState = rememberLazyListState()
    val itemWidth = 72.dp
    val contentPadding = if (useCompactPadding) {
        8.dp
    } else {
        (optionWidth - itemWidth).coerceAtLeast(0.dp) / 2
    }

    LazyRow(
        modifier
            .fillMaxWidth()
            .height(64.dp)
            .onGloballyPositioned {
                optionWidth = with(density) {
                    it.size.width.toDp()
                }
            },
        state = listState,
        contentPadding = PaddingValues(
            start = contentPadding,
            end = contentPadding,
        ),
    ) {
        items(options) { item ->
            Column(
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier
                    .width(itemWidth)
                    .fillMaxHeight()
                    .clickable {
                        onOptionSelected(item)
                    }
                    .animateItem(),
            ) {
                itemContent(item)
            }
        }
    }
}
