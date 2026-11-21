package me.rosuh.easywatermark.ui

import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import me.rosuh.easywatermark.shared.generated.resources.Res
import me.rosuh.easywatermark.shared.generated.resources.about_title_about
import me.rosuh.easywatermark.shared.generated.resources.action_pick
import me.rosuh.easywatermark.shared.generated.resources.action_save
import me.rosuh.easywatermark.shared.generated.resources.allStringResources
import me.rosuh.easywatermark.shared.generated.resources.dialog_save_export_progress
import me.rosuh.easywatermark.shared.generated.resources.font_import_summary
import me.rosuh.easywatermark.shared.generated.resources.font_panel_title
import me.rosuh.easywatermark.shared.generated.resources.pick_via_system
import me.rosuh.easywatermark.shared.generated.resources.preview_reset_view
import me.rosuh.easywatermark.shared.generated.resources.tips_pick_image
import org.jetbrains.compose.resources.ExperimentalResourceApi
import org.jetbrains.compose.resources.ResourceCaches
import org.jetbrains.compose.resources.clearBlocking
import org.jetbrains.compose.resources.getString

/**
 * S-i18n-1: product catalog lives in composeResources and resolves via generated [Res] accessors
 * (not bags / not Android R.string). Dual-write to app/res still exists until Phase 2.
 */
@OptIn(ExperimentalResourceApi::class)
class ComposeResourcesCatalogTest {

    @Test
    fun product_keys_en_match_default_catalog() {
        withJvmLocale(Locale.US) {
            runBlocking {
                assertEquals("Choose picture", getString(Res.string.action_pick))
                assertEquals("Save", getString(Res.string.action_save))
                assertEquals("About", getString(Res.string.about_title_about))
                assertEquals("Choose Images", getString(Res.string.tips_pick_image))
            }
        }
    }

    @Test
    fun product_keys_zh_cn_use_translated_catalog() {
        withJvmLocale(Locale.SIMPLIFIED_CHINESE) {
            runBlocking {
                val pick = getString(Res.string.action_pick)
                val about = getString(Res.string.about_title_about)
                // Must not silently fall back to EN for well-translated zh-rCN keys.
                assertTrue(pick != "Choose picture", "action_pick should be translated, got: $pick")
                assertTrue(about.isNotBlank())
                assertTrue(pick.isNotBlank())
                assertEquals("更多…", getString(Res.string.pick_via_system))
                assertEquals("字体", getString(Res.string.font_panel_title))
                assertEquals("重置视图", getString(Res.string.preview_reset_view))
                assertEquals("已添加 2 个，重复 1 个，失败 0 个", getString(Res.string.font_import_summary, 2, 1, 0))
                assertEquals("正在导出 1 / 3", getString(Res.string.dialog_save_export_progress, 1, 3))
            }
        }
    }

    @Test
    fun generated_map_contains_spike_and_product_keys() {
        val keys = Res.allStringResources.keys
        // Critical product keys (Phase 4 smoke — default catalog completeness).
        val critical = listOf(
            "cmp_spike_hello",
            "action_pick",
            "about_title_about",
            "tips_pick_image",
            "title_content",
            "title_style",
            "title_layout",
            "water_mark_mode_text",
            "recovery_title",
            "dialog_title_template_title",
        )
        for (k in critical) {
            assertTrue(k in keys, "missing critical key $k in catalog (${keys.size} total)")
        }
        assertTrue(keys.size >= 90, "expected full catalog, got ${keys.size}")
    }

    private fun withJvmLocale(locale: Locale, block: () -> Unit) {
        val previous = Locale.getDefault()
        try {
            Locale.setDefault(locale)
            ResourceCaches.clearBlocking()
            block()
        } finally {
            Locale.setDefault(previous)
            ResourceCaches.clearBlocking()
        }
    }
}
