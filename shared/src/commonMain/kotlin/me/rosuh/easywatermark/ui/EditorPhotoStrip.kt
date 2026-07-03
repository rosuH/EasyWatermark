package me.rosuh.easywatermark.ui

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.coerceAtLeast
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import me.rosuh.easywatermark.data.model.ImageInfo

/**
 * Shared CMP editor thumbnail strip.
 *
 * Android still supplies the thumbnail renderer because image loading is a platform edge today.
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
    val coroutineScope = rememberCoroutineScope()
    var stripWidth by remember {
        mutableStateOf(0.dp)
    }
    val itemWidth = 40.dp
    val density = LocalDensity.current
    LazyRow(
        modifier = modifier
            .onGloballyPositioned {
                stripWidth = with(density) {
                    it.size.width.toDp()
                }
            },
        contentPadding = PaddingValues(
            start = (stripWidth - itemWidth).coerceAtLeast(0.dp) / 2,
            end = (stripWidth - itemWidth).coerceAtLeast(0.dp) / 2,
        ),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        state = listState,
    ) {
        itemsIndexed(images) { index, imageInfo ->
            EditorPhotoStripItem(
                imageInfo = imageInfo,
                isSelected = imageInfo == selectedImage,
                modifier = Modifier
                    .size(itemWidth)
                    .padding(4.dp)
                    .animateItem(),
                onImageClick = { selectedImageInfo ->
                    coroutineScope.launch {
                        listState.animateScrollToItem(index)
                    }
                    onImageSelected(selectedImageInfo)
                },
                thumbnail = thumbnail,
            )
        }
    }
}

@Composable
fun EditorPhotoStripItem(
    imageInfo: ImageInfo,
    isSelected: Boolean,
    modifier: Modifier = Modifier,
    onImageClick: (ImageInfo) -> Unit = {},
    thumbnail: @Composable (imageInfo: ImageInfo, contentDescription: String, modifier: Modifier) -> Unit,
) {
    val border by animateDpAsState(targetValue = if (isSelected) 2.dp else 0.dp, label = "")
    val padding by animateDpAsState(targetValue = if (isSelected) 2.dp else 0.dp, label = "")
    Box(
        modifier = modifier
            .border(
                width = border,
                color = MaterialTheme.colorScheme.primary,
            )
            .padding(padding),
    ) {
        thumbnail(
            imageInfo,
            "image",
            Modifier
                .fillMaxSize()
                .clickable { onImageClick(imageInfo) },
        )
    }
}
