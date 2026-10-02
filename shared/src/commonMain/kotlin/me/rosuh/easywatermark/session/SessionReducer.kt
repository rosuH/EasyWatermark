package me.rosuh.easywatermark.session

import me.rosuh.easywatermark.data.model.ImageInfo
import me.rosuh.easywatermark.ui.Image
import me.rosuh.easywatermark.ui.LaunchScreenState
import me.rosuh.easywatermark.ui.LaunchScreenUiState
import me.rosuh.easywatermark.ui.UiState

/**
 * Pure UI/session transitions for [WatermarkSessionViewModel] (ADR-0017 Phase 1).
 */
data class SessionUiSnapshot(
    val launch: LaunchScreenState = LaunchScreenState(),
    val galleryPicked: List<Image>? = null,
    val dialogUi: UiState = UiState.None,
)

/**
 * Pure merge for concurrent launch updates during reducer publish.
 * If [live] diverged from [before] (e.g. [applyOffset] via StateFlow.update), prefer live
 * [ImageInfo] instances by URI so offsets/results are not overwritten by a stale reduced snapshot.
 */
internal fun mergeLaunchPreservingLiveImages(
    reduced: LaunchScreenState,
    live: LaunchScreenState,
    before: LaunchScreenState,
): LaunchScreenState {
    if (live == before) return reduced
    val liveByUri = live.selectedImageList.associateBy { it.uri }
    if (liveByUri.isEmpty()) return reduced
    val mergedList = reduced.selectedImageList.map { item ->
        liveByUri[item.uri] ?: item
    }
    val curUri = reduced.curImageInfo?.uri ?: live.curImageInfo?.uri
    val mergedCur = curUri?.let { uri -> liveByUri[uri] ?: reduced.curImageInfo }
        ?: reduced.curImageInfo
    return reduced.copy(
        selectedImageList = mergedList,
        curImageInfo = mergedCur,
    )
}

fun reduceSessionUi(snapshot: SessionUiSnapshot, intent: AppIntent): SessionUiSnapshot {
    return when (intent) {
        is AppIntent.GalleryLoaded -> {
            snapshot.copy(
                galleryPicked = intent.images,
                launch = snapshot.launch.copy(
                    uiState = LaunchScreenUiState.GalleryDialog,
                    imageList = intent.images,
                ),
            )
        }

        is AppIntent.ToggleGalleryItem -> {
            val current = snapshot.galleryPicked ?: return snapshot
            if (intent.index !in current.indices) return snapshot
            val newList = current.toMutableList().also {
                it[intent.index] = intent.image.copy(check = intent.checked)
            }
            snapshot.copy(
                galleryPicked = newList,
                launch = snapshot.launch.copy(imageList = newList),
            )
        }

        is AppIntent.DismissGallery -> {
            if (intent.selected) {
                val checked = snapshot.galleryPicked?.filter { it.check }.orEmpty()
                if (checked.isEmpty()) return snapshot
                val imageInfoList = checked.map { ImageInfo(it.uri) }
                snapshot.copy(
                    launch = snapshot.launch.copy(
                        uiState = LaunchScreenUiState.Editor,
                        imageList = snapshot.galleryPicked.orEmpty(),
                        selectedImageList = imageInfoList,
                        curImageInfo = imageInfoList.firstOrNull(),
                    ),
                )
            } else {
                snapshot.copy(
                    galleryPicked = emptyList(),
                    launch = snapshot.launch.copy(
                        uiState = LaunchScreenUiState.Launch,
                        imageList = emptyList(),
                    ),
                )
            }
        }

        AppIntent.ResetGalleryData -> {
            snapshot.copy(galleryPicked = emptyList())
        }

        is AppIntent.EnterEditor -> {
            // Historical product rule: empty selection is a no-op (never clears an existing list).
            if (intent.selected.isEmpty()) return snapshot
            snapshot.copy(
                launch = snapshot.launch.copy(
                    uiState = LaunchScreenUiState.Editor,
                    imageList = intent.gallerySnapshot,
                    selectedImageList = intent.selected,
                    waterMark = intent.waterMark,
                    curImageInfo = intent.selected.firstOrNull(),
                ),
            )
        }

        is AppIntent.SelectCurrent -> {
            if (snapshot.launch.curImageInfo?.uri == intent.ref) {
                snapshot
            } else {
                // Update focus in the same reduce so hosts can export immediately after selection.
                val match = snapshot.launch.selectedImageList.firstOrNull { it.uri == intent.ref }
                if (match == null) {
                    snapshot
                } else {
                    snapshot.copy(
                        launch = snapshot.launch.copy(curImageInfo = match),
                    )
                }
            }
        }

        AppIntent.NavigateBack -> {
            when (snapshot.launch.uiState) {
                LaunchScreenUiState.Editor -> {
                    // E2: discard transient batch selection on leave-editor; durable WaterMark stays in repo.
                    snapshot.copy(
                        launch = snapshot.launch.copy(
                            uiState = LaunchScreenUiState.Launch,
                            imageList = emptyList(),
                            selectedImageList = emptyList(),
                            curImageInfo = null,
                        ),
                    )
                }

                LaunchScreenUiState.GalleryDialog -> {
                    snapshot.copy(
                        galleryPicked = emptyList(),
                        launch = snapshot.launch.copy(
                            uiState = LaunchScreenUiState.Launch,
                            imageList = emptyList(),
                        ),
                    )
                }

                LaunchScreenUiState.About -> {
                    val returnTo = when (val r = snapshot.launch.aboutReturnUiState) {
                        LaunchScreenUiState.Launch, LaunchScreenUiState.Editor -> r
                        else -> LaunchScreenUiState.Launch
                    }
                    snapshot.copy(
                        launch = snapshot.launch.copy(uiState = returnTo),
                    )
                }

                LaunchScreenUiState.Launch -> {
                    snapshot.copy(
                        launch = snapshot.launch.copy(
                            uiState = LaunchScreenUiState.Launch,
                            imageList = emptyList(),
                        ),
                    )
                }
            }
        }

        is AppIntent.OpenAbout -> {
            val returnTo = when (intent.returnTo) {
                LaunchScreenUiState.Launch, LaunchScreenUiState.Editor -> intent.returnTo
                else -> LaunchScreenUiState.Launch
            }
            snapshot.copy(
                launch = snapshot.launch.copy(
                    uiState = LaunchScreenUiState.About,
                    aboutReturnUiState = returnTo,
                ),
            )
        }

        AppIntent.GoTemplate -> snapshot.copy(dialogUi = UiState.GoTemplate)
        AppIntent.GoEdit -> snapshot.copy(dialogUi = UiState.GoEdit)
        AppIntent.GoEditDialog -> snapshot.copy(dialogUi = UiState.GoEditDialog)
        AppIntent.ResetEditDialog -> snapshot.copy(dialogUi = UiState.None)
        is AppIntent.UseTemplate -> {
            snapshot.copy(dialogUi = UiState.UseTemplate(intent.template))
        }
        AppIntent.DatabaseError -> {
            snapshot.copy(dialogUi = UiState.DatabaseError)
        }

        is AppIntent.SyncWaterMark -> {
            snapshot.copy(launch = snapshot.launch.copy(waterMark = intent.waterMark))
        }

        // Side-effect intents handled in WatermarkSessionViewModel (not pure UI reduce).
        is AppIntent.RequestExport,
        AppIntent.CancelExport,
        is AppIntent.ApplyConfig,
        is AppIntent.ApplyTextStyle,
        -> snapshot
    }
}
