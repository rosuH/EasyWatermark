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
                SessionReduceResult(
                    snapshot = snapshot,
                    effects = listOf(SessionEffect.SelectImage(intent.ref)),
                )
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
            SessionReduceResult(
                snapshot.copy(launch = snapshot.launch.copy(curImageInfo = intent.info)),
            )
        }

        // Side-effect intents handled in WatermarkSessionViewModel (not pure UI reduce).
        is AppIntent.RequestExport,
        AppIntent.CancelExport,
        is AppIntent.ApplyConfig,
        is AppIntent.ApplyTextStyle,
        is AppIntent.ApplyOffset,
        -> SessionReduceResult(snapshot)
    }
}
