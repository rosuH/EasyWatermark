package me.rosuh.easywatermark.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.ScaffoldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.painter.Painter

/**
 * Shared CMP gallery-dialog shell.
 *
 * Selection is **local** ([mutableStateMapOf] by image id), matching production RecyclerView
 * Behavior (in-place check without list rebuild). Dismiss delivers the selected [Image] list once * after the exit animation (empty list = cancel).
 */
@Composable
fun GalleryDialogShell(
    images: List<Image>,
    title: String,
    closeIcon: Painter,
    searchIcon: Painter,
    checkIcon: Painter,
    selectedCountIcon: Painter,
    closeContentDescription: String,
    searchContentDescription: String,
    selectedCountContentDescription: String,
    modifier: Modifier = Modifier,
    contentWindowInsets: WindowInsets = ScaffoldDefaults.contentWindowInsets,
    backHandler: @Composable (onBack: () -> Unit) -> Unit = {},
    onLoadImages: () -> Unit,
    onDismiss: (selectedImages: List<Image>) -> Unit = {},
    onImageSelected: (image: Image, index: Int, isSelected: Boolean) -> Unit = { _, _, _ -> },
    onPickImageViaSystem: () -> Unit = {},
    /** Optional strip under the top bar (e.g. Android partial photo-access banner). */
    banner: (@Composable () -> Unit)? = null,
    thumbnail: @Composable (image: Image, contentDescription: String, modifier: Modifier) -> Unit,
) {
    // id → selected. Local only — never copy the full gallery list on each tap.
    val selectedIds = remember { mutableStateMapOf<Int, Boolean>() }
    var selectedCount by remember { mutableIntStateOf(0) }
    // Captured at dismiss start; delivered once after exit animation.
    var pendingDismissSelection by remember { mutableStateOf<List<Image>>(emptyList()) }

    val onLoadImagesState = rememberUpdatedState(onLoadImages)
    val onPickImageViaSystemState = rememberUpdatedState(onPickImageViaSystem)
    val onImageSelectedState = rememberUpdatedState(onImageSelected)
    val onDismissState = rememberUpdatedState(onDismiss)
    val imagesState = rememberUpdatedState(images)

    val isSelected: (Image) -> Boolean = remember {
        { image -> selectedIds[image.id] == true }
    }
    // Idempotent set — used by tap toggle and long-press drag range paint.
    val onSetSelected: (Image, Int, Boolean) -> Unit = remember {
        { image, index, selected ->
            val was = selectedIds[image.id] == true
            if (was != selected) {
                if (selected) {
                    selectedIds[image.id] = true
                    selectedCount++
                } else {
                    selectedIds.remove(image.id)
                    selectedCount = (selectedCount - 1).coerceAtLeast(0)
                }
                onImageSelectedState.value(image, index, selected)
            }
        }
    }

    fun snapshotSelected(): List<Image> {
        if (selectedIds.isEmpty()) return emptyList()
        return imagesState.value.filter { selectedIds[it.id] == true }
    }

    AnimatedTransitionHost(
        onDismissRequest = {
            onDismissState.value(pendingDismissSelection)
        },
        backHandler = backHandler,
    ) { dialogHelper ->
        // Load once when the dialog appears — do NOT key on images.size (that re-queried
        // MediaStore after the first load finished and spiked composition again).
        LaunchedEffect(Unit) {
            onLoadImagesState.value()
        }
        Scaffold(
            modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background),
            contentWindowInsets = contentWindowInsets,
            topBar = {
                GalleryDialogTopBarShell(
                    title = title,
                    closeIcon = closeIcon,
                    searchIcon = searchIcon,
                    closeContentDescription = closeContentDescription,
                    searchContentDescription = searchContentDescription,
                    onClose = {
                        pendingDismissSelection = emptyList()
                        dialogHelper.triggerDismiss()
                    },
                    onSearch = {
                        // System picker replaces in-app multi-select; treat as cancel of this sheet.
                        pendingDismissSelection = emptyList()
                        onPickImageViaSystemState.value()
                        dialogHelper.triggerDismiss()
                    },
                )
            },
        ) { innerPadding ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
            ) {
                banner?.invoke()
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                ) {
                    GalleryImageGrid(
                        images = images,
                        checkIcon = checkIcon,
                        isSelected = isSelected,
                        onSetSelected = onSetSelected,
                        thumbnail = thumbnail,
                    )

                    GallerySelectedCountFab(
                        selectedCount = selectedCount,
                        icon = selectedCountIcon,
                        contentDescription = selectedCountContentDescription,
                        modifier = Modifier.align(Alignment.BottomCenter),
                        onClick = {
                            pendingDismissSelection = snapshotSelected()
                            dialogHelper.triggerDismiss()
                        },
                    )
                }
            }
        }
    }
}
