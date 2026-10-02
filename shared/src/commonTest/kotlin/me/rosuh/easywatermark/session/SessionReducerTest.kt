package me.rosuh.easywatermark.session

import me.rosuh.easywatermark.data.model.MediaRef
import me.rosuh.easywatermark.ui.Image
import me.rosuh.easywatermark.ui.LaunchScreenUiState
import me.rosuh.easywatermark.ui.UiState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
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
        assertEquals(LaunchScreenUiState.GalleryDialog, r.launch.uiState)
        assertEquals(1, r.galleryPicked?.size)
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
        val picked = r.galleryPicked.orEmpty()
        assertEquals(true, picked[0].check)
        assertEquals(false, picked[1].check)
    }

    @Test
    fun dismissGallery_selected_commitsAndEntersEditor() {
        val base = SessionUiSnapshot(
            galleryPicked = listOf(img(1, checked = true), img(2, checked = false)),
        )
        val r = reduceSessionUi(base, AppIntent.DismissGallery(selected = true))
        assertEquals(LaunchScreenUiState.Editor, r.launch.uiState)
        assertEquals(1, r.launch.selectedImageList.size)
        assertSame(r.launch.selectedImageList.single(), r.launch.curImageInfo)
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
        assertEquals(LaunchScreenUiState.Launch, r.launch.uiState)
        assertTrue(r.galleryPicked!!.isEmpty())
    }

    @Test
    fun navigateBack_fromEditor_toLaunch() {
        val selected = listOf(
            me.rosuh.easywatermark.data.model.ImageInfo(MediaRef("content://batch/1")),
        )
        val base = SessionUiSnapshot(
            launch = me.rosuh.easywatermark.ui.LaunchScreenState(
                uiState = LaunchScreenUiState.Editor,
                imageList = listOf(img(1)),
                selectedImageList = selected,
                curImageInfo = selected.first(),
            ),
        )
        val r = reduceSessionUi(base, AppIntent.NavigateBack)
        assertEquals(LaunchScreenUiState.Launch, r.launch.uiState)
        assertTrue(r.launch.imageList.isEmpty())
        // E2: discard transient batch selection on leave-editor.
        assertTrue(r.launch.selectedImageList.isEmpty())
        assertEquals(null, r.launch.curImageInfo)
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
        assertEquals(LaunchScreenUiState.Launch, r.launch.uiState)
        assertTrue(r.galleryPicked!!.isEmpty())
    }

    /** E0 R1 — About from Launch returns to Launch. */
    @Test
    fun r1_openAboutFromLaunch_thenBack_toLaunch() {
        val opened = reduceSessionUi(
            SessionUiSnapshot(),
            AppIntent.OpenAbout(returnTo = LaunchScreenUiState.Launch),
        )
        assertEquals(LaunchScreenUiState.About, opened.launch.uiState)
        assertEquals(LaunchScreenUiState.Launch, opened.launch.aboutReturnUiState)
        val back = reduceSessionUi(opened, AppIntent.NavigateBack)
        assertEquals(LaunchScreenUiState.Launch, back.launch.uiState)
    }

    /** E0 R2 — About from Editor returns to Editor; selection preserved. */
    @Test
    fun r2_openAboutFromEditor_thenBack_toEditor_selectionPreserved() {
        val selected = listOf(
            me.rosuh.easywatermark.data.model.ImageInfo(MediaRef("content://sel/1")),
        )
        val editor = SessionUiSnapshot(
            launch = me.rosuh.easywatermark.ui.LaunchScreenState(
                uiState = LaunchScreenUiState.Editor,
                selectedImageList = selected,
                curImageInfo = selected.first(),
            ),
        )
        val opened = reduceSessionUi(
            editor,
            AppIntent.OpenAbout(returnTo = LaunchScreenUiState.Editor),
        )
        assertEquals(LaunchScreenUiState.About, opened.launch.uiState)
        assertEquals(LaunchScreenUiState.Editor, opened.launch.aboutReturnUiState)
        assertEquals(1, opened.launch.selectedImageList.size)
        val back = reduceSessionUi(opened, AppIntent.NavigateBack)
        assertEquals(LaunchScreenUiState.Editor, back.launch.uiState)
        assertEquals(1, back.launch.selectedImageList.size)
        assertEquals(MediaRef("content://sel/1"), back.launch.selectedImageList.first().uri)
    }

    /** E0 R3 — EnterEditor then NavigateBack → Launch. */
    @Test
    fun r3_enterEditor_thenNavigateBack_toLaunch() {
        val selected = listOf(
            me.rosuh.easywatermark.data.model.ImageInfo(MediaRef("content://e/1")),
        )
        val entered = reduceSessionUi(
            SessionUiSnapshot(),
            AppIntent.EnterEditor(selected = selected),
        )
        assertEquals(LaunchScreenUiState.Editor, entered.launch.uiState)
        val back = reduceSessionUi(entered, AppIntent.NavigateBack)
        assertEquals(LaunchScreenUiState.Launch, back.launch.uiState)
        assertTrue(back.launch.selectedImageList.isEmpty())
        assertEquals(null, back.launch.curImageInfo)
    }

    @Test
    fun templateDialogs_updateUiState() {
        assertEquals(
            UiState.GoTemplate,
            reduceSessionUi(SessionUiSnapshot(), AppIntent.GoTemplate).dialogUi,
        )
        assertEquals(
            UiState.None,
            reduceSessionUi(
                SessionUiSnapshot(dialogUi = UiState.GoTemplate),
                AppIntent.ResetEditDialog,
            ).dialogUi,
        )
    }

    @Test
    fun applyConfig_isNoOpOnUiSnapshot() {
        val r = reduceSessionUi(
            SessionUiSnapshot(),
            AppIntent.ApplyConfig(me.rosuh.easywatermark.data.model.WatermarkConfigChange.Text("x")),
        )
        assertEquals(LaunchScreenUiState.Launch, r.launch.uiState)
    }

    /** U0/E06 filmstrip: selection updates curImageInfo immediately. */
    @Test
    fun selectCurrent_updatesFocusImmediately() {
        val a = me.rosuh.easywatermark.data.model.ImageInfo(MediaRef("file:///a.jpg"))
        val b = me.rosuh.easywatermark.data.model.ImageInfo(MediaRef("file:///b.jpg"))
        val base = SessionUiSnapshot(
            launch = me.rosuh.easywatermark.ui.LaunchScreenState(
                uiState = LaunchScreenUiState.Editor,
                selectedImageList = listOf(a, b),
                curImageInfo = a,
            ),
        )
        val r = reduceSessionUi(base, AppIntent.SelectCurrent(b.uri))
        assertSame(b, r.launch.curImageInfo)
        assertEquals(b.uri, r.launch.curImageInfo?.uri)
    }

    @Test
    fun selectCurrent_sameRef_isNoOp() {
        val a = me.rosuh.easywatermark.data.model.ImageInfo(MediaRef("file:///a.jpg"))
        val base = SessionUiSnapshot(
            launch = me.rosuh.easywatermark.ui.LaunchScreenState(
                uiState = LaunchScreenUiState.Editor,
                selectedImageList = listOf(a),
                curImageInfo = a,
            ),
        )
        val r = reduceSessionUi(base, AppIntent.SelectCurrent(a.uri))
        assertSame(base, r)
    }

    @Test
    fun selectCurrent_missingRef_keepsSelection() {
        val a = me.rosuh.easywatermark.data.model.ImageInfo(MediaRef("file:///a.jpg"))
        val base = SessionUiSnapshot(
            launch = me.rosuh.easywatermark.ui.LaunchScreenState(
                uiState = LaunchScreenUiState.Editor,
                selectedImageList = listOf(a),
                curImageInfo = a,
            ),
        )
        assertSame(base, reduceSessionUi(base, AppIntent.SelectCurrent(MediaRef("file:///missing.jpg"))))
    }

    @Test
    fun navigateBack_thenSelectOldRef_doesNotRestoreDiscardedSelection() {
        val a = me.rosuh.easywatermark.data.model.ImageInfo(MediaRef("file:///a.jpg"))
        val entered = reduceSessionUi(SessionUiSnapshot(), AppIntent.EnterEditor(listOf(a)))
        val left = reduceSessionUi(entered, AppIntent.NavigateBack)
        val after = reduceSessionUi(left, AppIntent.SelectCurrent(a.uri))
        assertTrue(after.launch.selectedImageList.isEmpty())
        assertEquals(null, after.launch.curImageInfo)
    }
}
