package me.rosuh.easywatermark.render

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertSame

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
}
