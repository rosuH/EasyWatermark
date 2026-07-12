package me.rosuh.easywatermark.session

import me.rosuh.easywatermark.data.model.MediaRef
import me.rosuh.easywatermark.ui.Image
import me.rosuh.easywatermark.ui.LaunchScreenUiState
import me.rosuh.easywatermark.ui.UiState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class SessionReducerTest {

    private fun img(id: Int, checked: Boolean = false) = Image(
        id = id,
        uri = MediaRef("content://img/$id"),
        name = "n$id",
        size = 1L,
        date = 0L,
        check = checked,
    )

    @Test
    fun galleryLoaded_opensDialog() {
        val r = reduceSessionUi(SessionUiSnapshot(), AppIntent.GalleryLoaded(listOf(img(1))))
        assertEquals(LaunchScreenUiState.GalleryDialog, r.snapshot.launch.uiState)
        assertEquals(1, r.snapshot.galleryPicked?.size)
        assertTrue(r.effects.isEmpty())
    }

    @Test
    fun toggleGalleryItem_updatesCheck() {
        val base = SessionUiSnapshot(
            galleryPicked = listOf(img(1), img(2)),
            launch = me.rosuh.easywatermark.ui.LaunchScreenState(
                uiState = LaunchScreenUiState.GalleryDialog,
                imageList = listOf(img(1), img(2)),
            ),
        )
        val r = reduceSessionUi(
            base,
            AppIntent.ToggleGalleryItem(img(1), index = 0, checked = true),
        )
        val picked = r.snapshot.galleryPicked.orEmpty()
        assertEquals(true, picked[0].check)
        assertEquals(false, picked[1].check)
    }

    @Test
    fun dismissGallery_selected_commitsAndEntersEditor() {
        val base = SessionUiSnapshot(
            galleryPicked = listOf(img(1, checked = true), img(2, checked = false)),
        )
        val r = reduceSessionUi(base, AppIntent.DismissGallery(selected = true))
        assertEquals(LaunchScreenUiState.Editor, r.snapshot.launch.uiState)
        assertEquals(1, r.snapshot.launch.selectedImageList.size)
        assertIs<SessionEffect.CommitImageSelection>(r.effects.single())
    }

    @Test
    fun dismissGallery_cancel_returnsLaunch() {
        val base = SessionUiSnapshot(
            galleryPicked = listOf(img(1, checked = true)),
            launch = me.rosuh.easywatermark.ui.LaunchScreenState(
                uiState = LaunchScreenUiState.GalleryDialog,
            ),
        )
        val r = reduceSessionUi(base, AppIntent.DismissGallery(selected = false))
        assertEquals(LaunchScreenUiState.Launch, r.snapshot.launch.uiState)
        assertTrue(r.snapshot.galleryPicked!!.isEmpty())
        assertTrue(r.effects.isEmpty())
    }

    @Test
    fun navigateBack_fromEditor_toLaunch() {
        val base = SessionUiSnapshot(
            launch = me.rosuh.easywatermark.ui.LaunchScreenState(
                uiState = LaunchScreenUiState.Editor,
                imageList = listOf(img(1)),
            ),
        )
        val r = reduceSessionUi(base, AppIntent.NavigateBack)
        assertEquals(LaunchScreenUiState.Launch, r.snapshot.launch.uiState)
        assertTrue(r.snapshot.launch.imageList.isEmpty())
    }

    @Test
    fun navigateBack_fromGallery_clearsPicks() {
        val base = SessionUiSnapshot(
            galleryPicked = listOf(img(1)),
            launch = me.rosuh.easywatermark.ui.LaunchScreenState(
                uiState = LaunchScreenUiState.GalleryDialog,
            ),
        )
        val r = reduceSessionUi(base, AppIntent.NavigateBack)
        assertEquals(LaunchScreenUiState.Launch, r.snapshot.launch.uiState)
        assertTrue(r.snapshot.galleryPicked!!.isEmpty())
    }

    @Test
    fun templateDialogs_updateUiState() {
        assertEquals(
            UiState.GoTemplate,
            reduceSessionUi(SessionUiSnapshot(), AppIntent.GoTemplate).snapshot.dialogUi,
        )
        assertEquals(
            UiState.None,
            reduceSessionUi(
                SessionUiSnapshot(dialogUi = UiState.GoTemplate),
                AppIntent.ResetEditDialog,
            ).snapshot.dialogUi,
        )
    }
}
