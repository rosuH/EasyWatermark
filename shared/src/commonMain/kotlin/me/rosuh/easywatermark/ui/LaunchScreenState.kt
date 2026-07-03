package me.rosuh.easywatermark.ui

import me.rosuh.easywatermark.data.model.ImageInfo
import me.rosuh.easywatermark.data.model.WaterMark

data class LaunchScreenState(
    val uiState: LaunchScreenUiState = LaunchScreenUiState.Launch,
    val imageList: List<Image> = emptyList(),
    val selectedImageList: List<ImageInfo> = emptyList(),
    val waterMark: WaterMark = WaterMark.default,
    val curImageInfo: ImageInfo? = selectedImageList.firstOrNull(),
)
