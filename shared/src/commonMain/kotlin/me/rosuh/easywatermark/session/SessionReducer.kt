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
    if (reduced.uiState != LaunchScreenUiState.Editor) return reduced
    val liveByUri = live.selectedImageList.associateBy { it.uri }
    if (liveByUri.isEmpty()) return reduced
    val liveContinues = live.uiState == LaunchScreenUiState.Editor &&
        reduced.selectedImageList.any { it.uri in liveByUri }
    val mergedDefaultX = if (liveContinues) {
        live.defaultClampOffsetX ?: reduced.defaultClampOffsetX
    } else {
        reduced.defaultClampOffsetX
    }
    val mergedDefaultY = if (liveContinues) {
        live.defaultClampOffsetY ?: reduced.defaultClampOffsetY
    } else {
        reduced.defaultClampOffsetY
    }
    val mergedAnchor = if (liveContinues) {
        live.defaultClampAnchorUri?.takeIf { anchor ->
            reduced.selectedImageList.any { it.uri == anchor }
        } ?: reduced.defaultClampAnchorUri
    } else {
        reduced.defaultClampAnchorUri
    }
    val mergedList = reduced.selectedImageList.map { item ->
        liveByUri[item.uri] ?: if (
            mergedDefaultX != null &&
            mergedDefaultY != null &&
            !item.hasCustomOffset &&
            item.offsetX == 0.5f &&
            item.offsetY == 0.5f
        ) {
            item.copy(
                offsetX = mergedDefaultX,
                offsetY = mergedDefaultY,
                hasCustomOffset = false,
            )
        } else {
            item
        }
    }
    val curUri = reduced.curImageInfo?.uri ?: live.curImageInfo?.uri
    val mergedCur = curUri?.let { uri -> mergedList.firstOrNull { it.uri == uri } }
        ?: reduced.curImageInfo
    return reduced.copy(
        selectedImageList = mergedList,
        curImageInfo = mergedCur,
        defaultClampOffsetX = mergedDefaultX,
        defaultClampOffsetY = mergedDefaultY,
        defaultClampAnchorUri = mergedAnchor,
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
                        defaultClampOffsetX = null,
                        defaultClampOffsetY = null,
                        defaultClampAnchorUri = null,
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
            val existingByUri = snapshot.launch.selectedImageList.associateBy { it.uri }
            val continuingSession = snapshot.launch.uiState == LaunchScreenUiState.Editor &&
                intent.selected.any { it.uri in existingByUri }
            val defaultX = if (continuingSession) snapshot.launch.defaultClampOffsetX else null
            val defaultY = if (continuingSession) snapshot.launch.defaultClampOffsetY else null
            val defaultAnchor = if (
                continuingSession &&
                snapshot.launch.defaultClampAnchorUri != null &&
                intent.selected.any { it.uri == snapshot.launch.defaultClampAnchorUri }
            ) {
                snapshot.launch.defaultClampAnchorUri
            } else {
                null
            }
            val mergedSelected = if (!continuingSession) {
                intent.selected
            } else {
                intent.selected.map { incoming ->
                    val prior = existingByUri[incoming.uri]
                    when {
                        prior != null && incoming === prior -> incoming
                        prior != null &&
                            (prior.hasCustomOffset || prior.offsetX != 0.5f || prior.offsetY != 0.5f) &&
                            incoming.offsetX == 0.5f &&
                            incoming.offsetY == 0.5f &&
                            !incoming.hasCustomOffset -> incoming.copy(
                            offsetX = prior.offsetX,
                            offsetY = prior.offsetY,
                            hasCustomOffset = prior.hasCustomOffset,
                        )
                        prior == null &&
                            defaultX != null &&
                            defaultY != null &&
                            !incoming.hasCustomOffset &&
                            incoming.offsetX == 0.5f &&
                            incoming.offsetY == 0.5f -> incoming.copy(
                            offsetX = defaultX,
                            offsetY = defaultY,
                            hasCustomOffset = false,
                        )
                        else -> incoming
                    }
                }
            }
            snapshot.copy(
                launch = snapshot.launch.copy(
                    uiState = LaunchScreenUiState.Editor,
                    imageList = intent.gallerySnapshot,
                    selectedImageList = mergedSelected,
                    waterMark = intent.waterMark,
                    curImageInfo = mergedSelected.firstOrNull(),
                    defaultClampOffsetX = defaultX,
                    defaultClampOffsetY = defaultY,
                    defaultClampAnchorUri = defaultAnchor,
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
                            defaultClampOffsetX = null,
                            defaultClampOffsetY = null,
                            defaultClampAnchorUri = null,
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
