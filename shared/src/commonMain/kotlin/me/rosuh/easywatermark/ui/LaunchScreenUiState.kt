package me.rosuh.easywatermark.ui

/**
 * Session-owned product route (issue 12 P4.1 / E0).
 * Sole source of truth for Launch ↔ GalleryDialog ↔ Editor ↔ About.
 * Hosts must not mirror a parallel product-route type.
 */
sealed class LaunchScreenUiState {
    object Launch : LaunchScreenUiState()
    object GalleryDialog : LaunchScreenUiState()
    object Editor : LaunchScreenUiState()
    /** Full-screen About; return target is [LaunchScreenState.aboutReturnUiState]. */
    object About : LaunchScreenUiState()
}
