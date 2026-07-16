package me.rosuh.easywatermark.ui

import me.rosuh.easywatermark.data.model.FuncType
import me.rosuh.easywatermark.data.model.WatermarkConfigRules
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Pins the U1 Android-parity Content/Style/Layout catalogs that shared
 * [EditorBottomControls] ships — production editor order must stay aligned with
 * v2.10.0 Android bottom options (not reinvented per platform).
 */
class EditorOptionCatalogTest {

    @Test
    fun content_order_is_text_then_icon() {
        assertEquals(
            listOf(FuncType.Text, FuncType.Icon),
            EditorOptionCatalog.content.map { it.type },
        )
    }

    @Test
    fun style_order_matches_android_production() {
        assertEquals(
            listOf(
                FuncType.TileMode,
                FuncType.TextSize,
                FuncType.TextTypeFace,
                FuncType.Color,
                FuncType.Alpha,
                FuncType.Degree,
            ),
            EditorOptionCatalog.style.map { it.type },
        )
    }

    @Test
    fun layout_order_is_horizon_then_vertical() {
        assertEquals(
            listOf(FuncType.Horizon, FuncType.Vertical),
            EditorOptionCatalog.layout.map { it.type },
        )
    }

    @Test
    fun slider_ranges_match_config_rules() {
        val textSize = EditorOptionCatalog.style.first { it.type == FuncType.TextSize }
        assertEquals(WatermarkConfigRules.MIN_TEXT_SIZE, textSize.valueRange.start)
        assertEquals(WatermarkConfigRules.MAX_TEXT_SIZE, textSize.valueRange.endInclusive)

        val degree = EditorOptionCatalog.style.first { it.type == FuncType.Degree }
        assertEquals(0f, degree.valueRange.start)
        assertEquals(WatermarkConfigRules.MAX_DEGREE, degree.valueRange.endInclusive)

        val hGap = EditorOptionCatalog.layout.first { it.type == FuncType.Horizon }
        assertTrue(hGap.valueRange.endInclusive >= WatermarkConfigRules.MAX_HORIZONTAL_GAP.toFloat())
    }
}
