package me.rosuh.easywatermark.render

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toPixelMap
import me.rosuh.easywatermark.data.model.WaterMark
import me.rosuh.easywatermark.data.model.WatermarkMode
import me.rosuh.easywatermark.data.model.WatermarkTileMode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Real [CommonWatermarkPipeline.compose] exercise (ADR-0018) on Desktop/Skiko —
 * not a tile-id theater suite. Android dual-path + export-port gates live in `:app`.
 */
class CommonWatermarkPipelineComposeTest {

    private val env = desktopTextRasterEnv()

    @Test
    fun compose_text_repeat_overSolidBg_nonBlankSameDims() {
        val bg = solidBg(160, 120)
        val config = WaterMark.default.copy(
            text = "EasyWatermark",
            markMode = WatermarkMode.Text,
            tileMode = WatermarkTileMode.REPEAT,
            degree = 315f,
            textSize = 14f,
        )
        val out = CommonWatermarkPipeline.compose(
            background = bg,
            config = config,
            env = env,
            icon = null,
            offsetX = 0.5f,
            offsetY = 0.5f,
        )
        assertEquals(bg.width, out.width)
        assertEquals(bg.height, out.height)
        assertTrue(inkCount(out) > 0, "composed text must paint ink over background")
    }

    @Test
    fun compose_text_clamp_localizedVsRepeat() {
        val bg = solidBg(160, 120)
        val config = WaterMark.default.copy(
            text = "CLAMP",
            markMode = WatermarkMode.Text,
            degree = 0f,
            textSize = 18f,
        )
        val repeat = CommonWatermarkPipeline.compose(
            bg, config.copy(tileMode = WatermarkTileMode.REPEAT), env, null, 0.5f, 0.5f,
        )
        val clamp = CommonWatermarkPipeline.compose(
            bg, config.copy(tileMode = WatermarkTileMode.CLAMP), env, null, 0.2f, 0.2f,
        )
        assertTrue(inkCount(repeat) > 0)
        assertTrue(inkCount(clamp) > 0)
        // REPEAT tiles broadly; CLAMP is a single decal — typically fewer inked pixels.
        assertTrue(
            inkCount(clamp) <= inkCount(repeat),
            "CLAMP should not ink more than REPEAT on same bg (clamp=${inkCount(clamp)}, repeat=${inkCount(repeat)})",
        )
    }

    private fun solidBg(w: Int, h: Int): ImageBitmap =
        ImageBitmap(w, h).also { bmp ->
            // Leave transparent/black; compose draws over it.
        }

    private fun inkCount(bmp: ImageBitmap): Int {
        val px = bmp.toPixelMap()
        var n = 0
        for (y in 0 until px.height) {
            for (x in 0 until px.width) {
                if (px[x, y].alpha > 0.05f) n++
            }
        }
        return n
    }
}
