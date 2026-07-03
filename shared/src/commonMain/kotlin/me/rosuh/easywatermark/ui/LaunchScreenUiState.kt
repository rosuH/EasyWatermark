package me.rosuh.easywatermark.ui

sealed class LaunchScreenUiState {
    object Launch : LaunchScreenUiState()
    object GalleryDialog : LaunchScreenUiState()
    object Editor : LaunchScreenUiState()
}
