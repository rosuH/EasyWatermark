package me.rosuh.easywatermark.ui

import kotlin.test.Test
import kotlin.test.assertTrue
import me.rosuh.easywatermark.shared.generated.resources.Res
import me.rosuh.easywatermark.shared.generated.resources.allDrawableResources

/**
 * S-i18n-3: brand logo + product icons ship as composeResources drawables.
 */
class BrandLogoAssetTest {

    @Test
    fun product_drawables_are_in_compose_resources_map() {
        val keys = Res.allDrawableResources.keys
        assertTrue("ic_log_transparent" in keys, "missing logo in $keys")
        assertTrue("ic_about" in keys)
        assertTrue("ic_func_text" in keys)
        assertTrue("ic_save" in keys)
        assertTrue("ic_gallery_item_placeholder" in keys)
        assertTrue("ic_btn_color_picker" in keys)
        assertTrue("ic_remove_item" in keys)
        assertTrue(keys.size >= 30, "expected full product icon set, got ${keys.size}")
    }
}
