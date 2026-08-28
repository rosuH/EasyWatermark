package me.rosuh.easywatermark.render

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class OverlayPreviewPolicyTest {

    @Test
    fun no_selection_is_editor_empty() {
        assertEquals(
            OverlayPreviewChrome.EditorEmpty,
            OverlayPreviewPolicy.decide(
                selectedPath = null,
                photoPath = null,
                photoWidth = null,
                cellReadyForWidth = null,
                hasThumb = false,
                isTextMode = true,
            ),
        )
    }

    @Test
    fun selected_without_photo_and_with_thumb_is_wait_thumb() {
        val chrome = OverlayPreviewPolicy.decide(
            selectedPath = "/tmp/focus.jpg",
            photoPath = null,
            photoWidth = null,
            cellReadyForWidth = null,
            hasThumb = true,
            isTextMode = true,
        )
        assertEquals(OverlayPreviewChrome.WaitThumb, chrome)
        assertFalse(chrome == OverlayPreviewChrome.LiveLayers)
    }

    @Test
    fun selected_without_photo_or_thumb_is_wait_empty() {
        val chrome = OverlayPreviewPolicy.decide(
            selectedPath = "/tmp/focus.jpg",
            photoPath = null,
            photoWidth = null,
            cellReadyForWidth = null,
            hasThumb = false,
            isTextMode = true,
        )
        assertEquals(OverlayPreviewChrome.WaitEmpty, chrome)
        assertFalse(chrome == OverlayPreviewChrome.LiveLayers)
    }

    @Test
    fun text_mode_cell_built_for_640_cannot_live_over_800() {
        val chrome = OverlayPreviewPolicy.decide(
            selectedPath = "/tmp/focus.jpg",
            photoPath = "/tmp/focus.jpg",
            photoWidth = 800,
            cellReadyForWidth = 640,
            hasThumb = true,
            isTextMode = true,
        )
        assertEquals(OverlayPreviewChrome.WaitThumb, chrome)
        assertFalse(
            OverlayPreviewPolicy.canPublishLivePhoto(
                selectedPath = "/tmp/focus.jpg",
                photoPath = "/tmp/focus.jpg",
                photoWidth = 800,
                cellReadyForWidth = 640,
                isTextMode = true,
            ),
        )
    }

    @Test
    fun text_mode_same_width_is_live_layers() {
        assertEquals(
            OverlayPreviewChrome.LiveLayers,
            OverlayPreviewPolicy.decide(
                selectedPath = "/tmp/focus.jpg",
                photoPath = "/tmp/focus.jpg",
                photoWidth = 800,
                cellReadyForWidth = 800,
                hasThumb = true,
                isTextMode = true,
            ),
        )
        assertTrue(
            OverlayPreviewPolicy.canPublishLivePhoto(
                selectedPath = "/tmp/focus.jpg",
                photoPath = "/tmp/focus.jpg",
                photoWidth = 800,
                cellReadyForWidth = 800,
                isTextMode = true,
            ),
        )
    }

    @Test
    fun icon_mode_reuses_cell_across_widths() {
        assertEquals(
            OverlayPreviewChrome.LiveLayers,
            OverlayPreviewPolicy.decide(
                selectedPath = "/tmp/icon-src.jpg",
                photoPath = "/tmp/icon-src.jpg",
                photoWidth = 800,
                cellReadyForWidth = 640,
                hasThumb = false,
                isTextMode = false,
            ),
        )
        assertTrue(
            OverlayPreviewPolicy.canPublishLivePhoto(
                selectedPath = "/tmp/icon-src.jpg",
                photoPath = "/tmp/icon-src.jpg",
                photoWidth = 800,
                cellReadyForWidth = 640,
                isTextMode = false,
            ),
        )
    }

    @Test
    fun can_publish_live_photo_is_false_when_cell_missing() {
        assertFalse(
            OverlayPreviewPolicy.canPublishLivePhoto(
                selectedPath = "/tmp/focus.jpg",
                photoPath = "/tmp/focus.jpg",
                photoWidth = 800,
                cellReadyForWidth = null,
                isTextMode = true,
            ),
        )
        assertFalse(
            OverlayPreviewPolicy.canPublishLivePhoto(
                selectedPath = "/tmp/icon-src.jpg",
                photoPath = "/tmp/icon-src.jpg",
                photoWidth = 800,
                cellReadyForWidth = null,
                isTextMode = false,
            ),
        )
    }
}
