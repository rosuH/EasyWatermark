package me.rosuh.easywatermark.session

import me.rosuh.easywatermark.data.model.ImageInfo
import me.rosuh.easywatermark.data.model.MediaRef
import me.rosuh.easywatermark.ui.Image
import me.rosuh.easywatermark.ui.LaunchScreenState
import me.rosuh.easywatermark.ui.LaunchScreenUiState
import me.rosuh.easywatermark.ui.UiState

/**
 * Pure UI/session transitions for [WatermarkSessionViewModel] (ADR-0017 Phase 1).
 * Side effects (repo writes) are returned as [SessionEffect] for the ViewModel to execute.
 */
data class SessionUiSnapshot(
    val launch: LaunchScreenState = LaunchScreenState(),
    val galleryPicked: List<Image>? = null,
    val dialogUi: UiState = UiState.None,
)

sealed class SessionEffect {
    data class CommitImageSelection(val list: List<ImageInfo>) : SessionEffect()
    data class SelectImage(val ref: MediaRef) : SessionEffect()
}

data class SessionReduceResult(
    val snapshot: SessionUiSnapshot,
    val effects: List<SessionEffect> = emptyList(),
)

/**
 * Pure rule: apply [info] as current image and replace any [LaunchScreenState.selectedImageList]
 * entry with the same [ImageInfo.uri]. Shared by [reduceSessionUi] and the session VM's
 * synchronous offset commit (export must see post-drag offsets without a global intent actor).
 */
internal fun applyCurrentImageToLaunch(
    launch: LaunchScreenState,
    info: ImageInfo?,
): LaunchScreenState {
    if (info == null) {
        return launch.copy(curImageInfo = null)
    }
    val list = launch.selectedImageList
    val updatedList = if (list.any { it.uri == info.uri }) {
        list.map { item -> if (item.uri == info.uri) info else item }
    } else {
        list
    }
    return launch.copy(
        curImageInfo = info,
        selectedImageList = updatedList,
    )
}

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

fun reduceSessionUi(snapshot: SessionUiSnapshot, intent: AppIntent): SessionReduceResult {
    return when (intent) {
        is AppIntent.GalleryLoaded -> {
            SessionReduceResult(
                snapshot.copy(
                    galleryPicked = intent.images,
                    launch = snapshot.launch.copy(
                        uiState = LaunchScreenUiState.GalleryDialog,
                        imageList = intent.images,
                    ),
                ),
            )
        }

        is AppIntent.ToggleGalleryItem -> {
            val current = snapshot.galleryPicked ?: return SessionReduceResult(snapshot)
            if (intent.index !in current.indices) return SessionReduceResult(snapshot)
            val newList = current.toMutableList().also {
                it[intent.index] = intent.image.copy(check = intent.checked)
            }
            SessionReduceResult(
                snapshot.copy(
                    galleryPicked = newList,
                    launch = snapshot.launch.copy(imageList = newList),
                ),
            )
        }

        is AppIntent.DismissGallery -> {
            if (intent.selected) {
                val checked = snapshot.galleryPicked?.filter { it.check }.orEmpty()
                if (checked.isEmpty()) return SessionReduceResult(snapshot)
                val imageInfoList = checked.map { ImageInfo(it.uri) }
                SessionReduceResult(
                    snapshot = snapshot.copy(
                        launch = snapshot.launch.copy(
                            uiState = LaunchScreenUiState.Editor,
                            imageList = snapshot.galleryPicked.orEmpty(),
                            selectedImageList = imageInfoList,
                            curImageInfo = imageInfoList.firstOrNull(),
                        ),
                    ),
                    effects = listOf(SessionEffect.CommitImageSelection(imageInfoList)),
                )
            } else {
                SessionReduceResult(
                    snapshot.copy(
                        galleryPicked = emptyList(),
                        launch = snapshot.launch.copy(
                            uiState = LaunchScreenUiState.Launch,
                            imageList = emptyList(),
                        ),
                    ),
                )
            }
        }

        AppIntent.ResetGalleryData -> {
            SessionReduceResult(snapshot.copy(galleryPicked = emptyList()))
        }

        is AppIntent.EnterEditor -> {
            if (intent.selected.isEmpty()) return SessionReduceResult(snapshot)
            SessionReduceResult(
                snapshot = snapshot.copy(
                    launch = snapshot.launch.copy(
                        uiState = LaunchScreenUiState.Editor,
                        imageList = intent.gallerySnapshot,
                        selectedImageList = intent.selected,
                        waterMark = intent.waterMark,
                        curImageInfo = intent.selected.firstOrNull(),
                    ),
                ),
                effects = listOf(SessionEffect.CommitImageSelection(intent.selected)),
            )
        }

        is AppIntent.SelectCurrent -> {
            if (snapshot.launch.curImageInfo?.uri == intent.ref) {
                SessionReduceResult(snapshot)
            } else {
                // Must update curImageInfo in the same reduce — hosts (iOS) export immediately
                // after SelectCurrent; relying on async SyncCurrentImage races and exports the wrong image.
                val match = snapshot.launch.selectedImageList.firstOrNull { it.uri == intent.ref }
                if (match == null) {
                    SessionReduceResult(snapshot)
                } else {
                    SessionReduceResult(
                        snapshot = snapshot.copy(
                            launch = snapshot.launch.copy(curImageInfo = match),
                        ),
                        effects = listOf(SessionEffect.SelectImage(intent.ref)),
                    )
                }
            }
        }

        AppIntent.NavigateBack -> {
            when (snapshot.launch.uiState) {
                LaunchScreenUiState.Editor -> {
                    SessionReduceResult(
                        snapshot.copy(
                            launch = snapshot.launch.copy(
                                uiState = LaunchScreenUiState.Launch,
                                imageList = emptyList(),
                            ),
                        ),
                    )
                }

                LaunchScreenUiState.GalleryDialog -> {
                    SessionReduceResult(
                        snapshot.copy(
                            galleryPicked = emptyList(),
                            launch = snapshot.launch.copy(
                                uiState = LaunchScreenUiState.Launch,
                                imageList = emptyList(),
                            ),
                        ),
                    )
                }

                LaunchScreenUiState.Launch -> {
                    SessionReduceResult(
                        snapshot.copy(
                            launch = snapshot.launch.copy(
                                uiState = LaunchScreenUiState.Launch,
                                imageList = emptyList(),
                            ),
                        ),
                    )
                }
            }
        }

        AppIntent.GoTemplate -> SessionReduceResult(snapshot.copy(dialogUi = UiState.GoTemplate))
        AppIntent.GoEdit -> SessionReduceResult(snapshot.copy(dialogUi = UiState.GoEdit))
        AppIntent.GoEditDialog -> SessionReduceResult(snapshot.copy(dialogUi = UiState.GoEditDialog))
        AppIntent.ResetEditDialog -> SessionReduceResult(snapshot.copy(dialogUi = UiState.None))
        is AppIntent.UseTemplate -> {
            SessionReduceResult(snapshot.copy(dialogUi = UiState.UseTemplate(intent.template)))
        }
        AppIntent.DatabaseError -> {
            SessionReduceResult(snapshot.copy(dialogUi = UiState.DatabaseError))
        }

        is AppIntent.SyncWaterMark -> {
            SessionReduceResult(
                snapshot.copy(launch = snapshot.launch.copy(waterMark = intent.waterMark)),
            )
        }

        is AppIntent.SyncCurrentImage -> {
            // Keep session snapshot self-consistent: hosts export from selectedImageList
            // (ComposeMainActivity.doExport), not only curImageInfo.
            SessionReduceResult(
                snapshot.copy(launch = applyCurrentImageToLaunch(snapshot.launch, intent.info)),
            )
        }

        // Side-effect intents handled in WatermarkSessionViewModel (not pure UI reduce).
        is AppIntent.RequestExport,
        AppIntent.CancelExport,
        is AppIntent.ApplyConfig,
        is AppIntent.ApplyTextStyle,
        -> SessionReduceResult(snapshot)
    }
}
