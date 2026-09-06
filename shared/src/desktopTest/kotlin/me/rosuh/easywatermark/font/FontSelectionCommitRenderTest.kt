package me.rosuh.easywatermark.font

import androidx.compose.ui.graphics.asSkiaBitmap
import kotlinx.coroutines.runBlocking
import me.rosuh.easywatermark.data.model.TextTypeface
import me.rosuh.easywatermark.data.model.WaterMark
import me.rosuh.easywatermark.data.model.WatermarkFontRef
import me.rosuh.easywatermark.data.model.WatermarkMode
import me.rosuh.easywatermark.render.CommonWatermarkPipeline
import me.rosuh.easywatermark.render.DesktopWatermarkTextRenderer
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FontSelectionCommitRenderTest {

    @Test
    fun delayed_session_publication_paints_committed_font_matching_export() = runBlocking {
        val access = DesktopWatermarkFontAccess(
            root = File("build/tmp-font-commit-${System.nanoTime()}"),
        )
        val system = access.listSystemFonts()
        val candidate = system.firstOrNull {
            val name = it.displayName.lowercase()
            name.contains("times") || name.contains("courier") || name.contains("menlo") ||
                name.contains("monaco") || name.contains("serif")
        } ?: system.first()
        val resolvedB = access.resolve(candidate.ref)
        assertIs<FontResolution.Success>(resolvedB)

        val publishedA = WaterMark.default.copy(
            text = "CommitOrder",
            markMode = WatermarkMode.Text,
            fontRef = WatermarkFontRef.Default,
        )
        assertEquals(WatermarkFontRef.Default, publishedA.fontRef)

        val committed = FontSelectionCommit.waterMarkForRender(
            persistSucceeded = true,
            requestGeneration = 1,
            currentGeneration = 1,
            published = publishedA,
            committedRef = candidate.ref,
            supportedStyles = resolvedB.supportedStyles.styles,
        )
        assertEquals(candidate.ref, committed!!.fontRef)

        val env = DesktopWatermarkTextRenderer.textRasterEnv()
        val preview = CommonWatermarkPipeline.composeCell(
            imageWidth = 360,
            config = committed,
            env = env,
            fontFamily = resolvedB.family,
        )
        val export = CommonWatermarkPipeline.composeCell(
            imageWidth = 360,
            config = committed,
            env = env,
            fontFamily = resolvedB.family,
        )
        val previewBytes = preview.asSkiaBitmap().readPixels()
        val exportBytes = export.asSkiaBitmap().readPixels()
        assertEquals(preview.width, export.width)
        assertEquals(preview.height, export.height)
        assertEquals(previewBytes?.contentHashCode(), exportBytes?.contentHashCode())

        val staleLaunchPaint = CommonWatermarkPipeline.composeCell(
            imageWidth = 360,
            config = publishedA,
            env = env,
            fontFamily = androidx.compose.ui.text.font.FontFamily.Default,
        )
        val staleBytes = staleLaunchPaint.asSkiaBitmap().readPixels()
        val distinctFromStale = preview.width != staleLaunchPaint.width ||
            preview.height != staleLaunchPaint.height ||
            previewBytes?.contentHashCode() != staleBytes?.contentHashCode()
        assertTrue(distinctFromStale, "Committed B must not paint as unpublished A")
    }

    @Test
    fun persist_failure_and_stale_generation_do_not_paint() {
        val published = WaterMark.default.copy(textTypeface = TextTypeface.Bold)
        val ref = WatermarkFontRef.System("desktop", "Courier")
        assertNull(
            FontSelectionCommit.waterMarkForRender(
                persistSucceeded = false,
                requestGeneration = 1,
                currentGeneration = 1,
                published = published,
                committedRef = ref,
                supportedStyles = FontStyleCapability.All.styles,
            ),
        )
        assertNull(
            FontSelectionCommit.waterMarkForRender(
                persistSucceeded = true,
                requestGeneration = 1,
                currentGeneration = 2,
                published = published,
                committedRef = ref,
                supportedStyles = FontStyleCapability.All.styles,
            ),
        )
        val ok = FontSelectionCommit.waterMarkForRender(
            persistSucceeded = true,
            requestGeneration = 3,
            currentGeneration = 3,
            published = published,
            committedRef = ref,
            supportedStyles = setOf(TextTypeface.Normal),
        )
        assertEquals(ref, ok!!.fontRef)
        assertEquals(TextTypeface.Normal, ok.textTypeface)
        assertNotEquals(TextTypeface.Bold, ok.textTypeface)
    }
}
