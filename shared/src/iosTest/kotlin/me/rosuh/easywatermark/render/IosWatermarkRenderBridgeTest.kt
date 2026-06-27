package me.rosuh.easywatermark.render

import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.ImageBitmapConfig
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * S4d-31: compile/link proof for the iOS Swift-catchable render boundary
 * ([IosWatermarkRenderBridge]/[IosRenderException]/[IosRenderedPng]).
 *
 * Like the other `iosTest` suites, these RUN only on an iOS runtime (`iosSimulatorArm64Test`), which is
 * not installed here — so this slice's proof is **compile + native test-executable LINK** (the API
 * shape, the `@Throws` mapping, and the failure-wrapping contract are exercised by the asserts, which
 * RUN at C5.3). No font/bundle/runtime dependency is required to compile.
 */
class IosWatermarkRenderBridgeTest {

    @Test
    fun render_exception_carries_stage_and_message() {
        val ex = IosRenderException(IosRenderStage.RENDER, "boom", null)
        assertSame(IosRenderStage.RENDER, ex.stage, "stage must round-trip")
        assertEquals("boom", ex.message, "message must round-trip")
        assertNull(ex.cause, "cause must round-trip (null here)")
    }

    @Test
    fun render_exception_preserves_cause() {
        val cause = IllegalStateException("inner")
        val ex = IosRenderException(IosRenderStage.ENCODE, "wrap", cause)
        assertSame(cause, ex.cause, "the original throwable must be preserved as the cause")
        assertSame(IosRenderStage.ENCODE, ex.stage)
    }

    @Test
    fun rendered_png_holds_fields() {
        val holder = IosRenderedPng(png = byteArrayOf(1, 2, 3), width = 4, height = 6)
        assertEquals(3, holder.png.size, "png bytes must round-trip")
        assertEquals(4, holder.width, "width must round-trip")
        assertEquals(6, holder.height, "height must round-trip")
    }

    @Test
    fun stage_enum_has_three_pipeline_stages() {
        assertEquals(3, IosRenderStage.entries.size, "FONT/RENDER/ENCODE")
    }

    @Test
    fun bridge_wraps_bad_input_as_render_exception() {
        // Undecodable bytes + a default (likely font-less in the test bundle) environment: whichever
        // stage fails first, the bridge must surface a single Swift-catchable [IosRenderException]
        // (never a raw IllegalState/IllegalArgument that would crash across the Swift boundary).
        assertFailsWith<IosRenderException> {
            IosWatermarkRenderBridge.renderWatermarkedPng(
                imageBytes = byteArrayOf(1, 2, 3, 4, 5),
                text = "x",
            )
        }
    }

    /** A small solid-color encoded PNG, used as deterministic background / icon bytes (no font needed). */
    private fun pngBytes(w: Int, h: Int, color: Long): ByteArray {
        val bmp = ImageBitmap(w, h, ImageBitmapConfig.Argb8888)
        CanvasDrawScope().draw(Density(1f), LayoutDirection.Ltr, Canvas(bmp), Size(w.toFloat(), h.toFloat())) {
            drawRect(color = Color(color))
        }
        return IosWatermarkRenderer.encodePng(bmp)
    }

    /**
     * S4d-117: the icon render entry composes the background + icon end-to-end (no FONT stage) and returns a
     * PNG sized to the background. RUNS on `iosSimulatorArm64Test`.
     */
    @Test
    fun icon_bridge_renders_png_sized_to_background() {
        val bg = pngBytes(64, 48, 0xFF202020)
        val icon = pngBytes(16, 16, 0xFFEE22AA)
        val out = IosWatermarkRenderBridge.renderIconWatermarkedPng(imageBytes = bg, iconBytes = icon)
        assertEquals(64, out.width, "composed width == background width")
        assertEquals(48, out.height, "composed height == background height")
        assertTrue(out.png.size > 8, "encoded icon-watermark PNG must be non-trivial")
    }

    /**
     * S4d-117: a decode failure on the icon bytes (or background) is wrapped as a single Swift-catchable
     * [IosRenderException] (stage RENDER), never a raw Kotlin exception across the Swift boundary.
     */
    @Test
    fun icon_bridge_wraps_bad_icon_bytes_as_render_exception() {
        val bg = pngBytes(32, 32, 0xFF000000)
        val ex = assertFailsWith<IosRenderException> {
            IosWatermarkRenderBridge.renderIconWatermarkedPng(
                imageBytes = bg,
                iconBytes = byteArrayOf(1, 2, 3, 4, 5),
            )
        }
        assertSame(IosRenderStage.RENDER, ex.stage, "undecodable icon bytes must fail at the RENDER stage")
    }
}
