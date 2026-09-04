package me.rosuh.easywatermark.render

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PreviewPaintPolicyTest {

    @Test
    fun same_path_config_refresh_must_not_flash_unwatermarked_source() {
        assertFalse(
            PreviewPaintPolicy.showSourceWhileComposing(
                displayedOwnedPath = "/tmp/focus.jpg",
                requestOwnedPath = "/tmp/focus.jpg",
            ),
            "slider / style ticks must keep the last Watermarked frame",
        )
    }

    @Test
    fun different_path_or_empty_display_may_show_source_as_first_paint() {
        assertTrue(
            PreviewPaintPolicy.showSourceWhileComposing(
                displayedOwnedPath = "/tmp/old.jpg",
                requestOwnedPath = "/tmp/next.jpg",
            ),
        )
        assertTrue(
            PreviewPaintPolicy.showSourceWhileComposing(
                displayedOwnedPath = null,
                requestOwnedPath = "/tmp/first.jpg",
            ),
        )
    }
}
