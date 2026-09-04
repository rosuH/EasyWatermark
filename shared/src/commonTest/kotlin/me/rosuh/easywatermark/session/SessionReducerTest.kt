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
        assertEquals(LaunchScreenUiState.Launch, r.snapshot.launch.uiState)
        assertTrue(r.snapshot.launch.imageList.isEmpty())
        // E2: discard transient batch selection on leave-editor.
        assertTrue(r.snapshot.launch.selectedImageList.isEmpty())
        assertEquals(null, r.snapshot.launch.curImageInfo)
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

    /** E0 R1 — About from Launch returns to Launch. */
    @Test
    fun r1_openAboutFromLaunch_thenBack_toLaunch() {
        val opened = reduceSessionUi(
            SessionUiSnapshot(),
            AppIntent.OpenAbout(returnTo = LaunchScreenUiState.Launch),
        )
        assertEquals(LaunchScreenUiState.About, opened.snapshot.launch.uiState)
        assertEquals(LaunchScreenUiState.Launch, opened.snapshot.launch.aboutReturnUiState)
        val back = reduceSessionUi(opened.snapshot, AppIntent.NavigateBack)
        assertEquals(LaunchScreenUiState.Launch, back.snapshot.launch.uiState)
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
        assertEquals(LaunchScreenUiState.About, opened.snapshot.launch.uiState)
        assertEquals(LaunchScreenUiState.Editor, opened.snapshot.launch.aboutReturnUiState)
        assertEquals(1, opened.snapshot.launch.selectedImageList.size)
        val back = reduceSessionUi(opened.snapshot, AppIntent.NavigateBack)
        assertEquals(LaunchScreenUiState.Editor, back.snapshot.launch.uiState)
        assertEquals(1, back.snapshot.launch.selectedImageList.size)
        assertEquals(MediaRef("content://sel/1"), back.snapshot.launch.selectedImageList.first().uri)
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
        assertEquals(LaunchScreenUiState.Editor, entered.snapshot.launch.uiState)
        val back = reduceSessionUi(entered.snapshot, AppIntent.NavigateBack)
        assertEquals(LaunchScreenUiState.Launch, back.snapshot.launch.uiState)
        assertTrue(back.snapshot.launch.selectedImageList.isEmpty())
        assertEquals(null, back.snapshot.launch.curImageInfo)
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

    @Test
    fun applyConfig_isNoOpOnUiSnapshot() {
        val r = reduceSessionUi(
            SessionUiSnapshot(),
            AppIntent.ApplyConfig(me.rosuh.easywatermark.data.model.WatermarkConfigChange.Text("x")),
        )
        assertEquals(LaunchScreenUiState.Launch, r.snapshot.launch.uiState)
        assertTrue(r.effects.isEmpty())
    }

    /** U0/E06 filmstrip: selection updates curImageInfo immediately + SelectImage effect. */
    @Test
    fun selectCurrent_emitsSelectImageEffect() {
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
        val effect = r.effects.single()
        assertIs<SessionEffect.SelectImage>(effect)
        assertEquals(b.uri, effect.ref)
        // Critical for iOS: curImageInfo must flip in the same reduce (not async SyncCurrentImage).
        assertEquals(b.uri, r.snapshot.launch.curImageInfo?.uri)
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
        assertTrue(r.effects.isEmpty())
    }

    /**
 * CLAMP drag → [AppIntent.SyncCurrentImage] must update **both** curImageInfo and the
 * Matching selectedImageList entry. Export uses selectedImageList (not only cur).     */
    @Test
    fun syncCurrentImage_replacesMatchingSelectedListOffsets() {
        val a = me.rosuh.easywatermark.data.model.ImageInfo(
            MediaRef("file:///a.jpg"),
            offsetX = 0.5f,
            offsetY = 0.5f,
        )
        val b = me.rosuh.easywatermark.data.model.ImageInfo(
            MediaRef("file:///b.jpg"),
            offsetX = 0.5f,
            offsetY = 0.5f,
        )
        val base = SessionUiSnapshot(
            launch = me.rosuh.easywatermark.ui.LaunchScreenState(
                uiState = LaunchScreenUiState.Editor,
                selectedImageList = listOf(a, b),
                curImageInfo = a,
            ),
        )
        val draggedA = a.copy(offsetX = 0.12f, offsetY = 0.88f)
        val r = reduceSessionUi(base, AppIntent.SyncCurrentImage(draggedA))

        val cur = r.snapshot.launch.curImageInfo
        assertEquals(a.uri, cur?.uri)
        assertEquals(0.12f, cur?.offsetX)
        assertEquals(0.88f, cur?.offsetY)

        // List entry for A must carry new offsets (export input for hosts that read selectedImageList).
        val exportList = r.snapshot.launch.selectedImageList
        assertEquals(2, exportList.size)
        val exportA = exportList.first { it.uri == a.uri }
        val exportB = exportList.first { it.uri == b.uri }
        assertEquals(0.12f, exportA.offsetX)
        assertEquals(0.88f, exportA.offsetY)
        assertEquals(0.5f, exportB.offsetX)
        assertEquals(0.5f, exportB.offsetY)
        assertEquals(draggedA.offsetX, exportList[0].offsetX)
    }

    @Test
    fun syncCurrentImage_null_clearsCurOnly() {
        val a = me.rosuh.easywatermark.data.model.ImageInfo(MediaRef("file:///a.jpg"))
        val base = SessionUiSnapshot(
            launch = me.rosuh.easywatermark.ui.LaunchScreenState(
                uiState = LaunchScreenUiState.Editor,
                selectedImageList = listOf(a),
                curImageInfo = a,
            ),
        )
        val r = reduceSessionUi(base, AppIntent.SyncCurrentImage(null))
        assertEquals(null, r.snapshot.launch.curImageInfo)
        assertEquals(1, r.snapshot.launch.selectedImageList.size)
        assertEquals(a.uri, r.snapshot.launch.selectedImageList.single().uri)
    }
}
