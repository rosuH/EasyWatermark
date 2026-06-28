package me.rosuh.easywatermark.render

import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import me.rosuh.easywatermark.data.model.TextPaintStyle
import me.rosuh.easywatermark.data.model.TextTypeface
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * S4d-195: pins the shared commonMain Compose text-style mappings ([toComposeFontStyle] /
 * [toComposeDrawStyle]) that replaced the formerly-duplicated private functions in the Desktop and iOS
 * renderers. Asserts the exact branch values so the consolidation stays value-preserving (no render
 * change, no golden rebaseline). Runs on every `:shared` target's test source set (executed on
 * `:shared:desktopTest` and `:shared:iosSimulatorArm64Test`).
 */
class ComposeTextStyleMappingTest {

    @Test
    fun typeface_maps_to_compose_weight_and_style() {
        assertEquals(FontWeight.Normal to FontStyle.Normal, TextTypeface.Normal.toComposeFontStyle())
        assertEquals(FontWeight.Normal to FontStyle.Italic, TextTypeface.Italic.toComposeFontStyle())
        assertEquals(FontWeight.Bold to FontStyle.Normal, TextTypeface.Bold.toComposeFontStyle())
        assertEquals(FontWeight.Bold to FontStyle.Italic, TextTypeface.BoldItalic.toComposeFontStyle())
    }

    @Test
    fun paint_style_maps_to_compose_draw_style() {
        assertEquals(Fill, TextPaintStyle.Fill.toComposeDrawStyle())
        // Stroke overrides equals (width/cap/join/miter/pathEffect); default Stroke() == default Stroke().
        assertEquals(Stroke(), TextPaintStyle.Stroke.toComposeDrawStyle())
    }
}
