package me.rosuh.easywatermark.uitest

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.v2.runSkikoComposeUiTest
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.annotation.DelicateCoilApi
import coil3.request.ImageRequest
import coil3.size.Precision
import coil3.size.Scale
import kotlin.test.Test
import kotlinx.coroutines.runBlocking
import me.rosuh.easywatermark.data.model.ImageInfoUi
import me.rosuh.easywatermark.data.model.MediaRef
import me.rosuh.easywatermark.data.model.Sticker
import me.rosuh.easywatermark.data.model.WaterMark
import me.rosuh.easywatermark.data.model.WatermarkTileMode
import me.rosuh.easywatermark.render.OverlayPreviewChrome
import me.rosuh.easywatermark.ui.EditorLayoutClass
import me.rosuh.easywatermark.ui.LiveOverlayPreview
import me.rosuh.easywatermark.ui.LocalPreviewInteraction
import me.rosuh.easywatermark.ui.PreviewInteraction
import me.rosuh.easywatermark.ui.compose.BuiltInSticker
import me.rosuh.easywatermark.ui.image.ProductThumb
import me.rosuh.easywatermark.ui.image.buildProductImageLoader
import me.rosuh.easywatermark.ui.image.productThumbCacheKey
import me.rosuh.easywatermark.ui.overlayCellFrom
import me.rosuh.easywatermark.ui.theme.AppTheme
import me.rosuh.easywatermark.ui.theme.MotionPolicy
import me.rosuh.easywatermark.ui.theme.ProvideMotionPolicy
import okio.FileSystem

/**
 * Sticker panel v3 design-review frames. Evidence only (ADR-0010 / ADR-0032): each case stages one
 * state, asserts the key node is there and dumps `shared/build/l1-witness/<name>.png`. No pixel or
 * hash assertion — a human looks at these.
 *
 * Reuses the contract harness in `StickerPanelSheetUiTest` so the frames show the real
 * `EditorScreen` + rail + panel, not a mock.
 */
@OptIn(ExperimentalTestApi::class)
class StickerPanelWitnessUiTest {

    // ----- 1.1 rail -------------------------------------------------------------------------

    @Test
    fun stickerRailEmptyLibrary() = witnessFrame {
        showEditorWitness(StickerHarness())
        tag("stickerInlineRail").assertExists()
        captureFrame("stickerRailEmptyLibrary")
    }

    @Test
    fun stickerRailWithLibrary() = witnessFrame {
        val stickers = witnessStickers()
        showEditorWitness(StickerHarness(icon = appliedUserIcon(stickers), stickers = stickers))
        tag("stickerInlineRail").assertExists()
        captureFrame("stickerRailWithLibrary")
    }

    @Test
    fun stickerRailBuiltInApplied() = witnessFrame {
        val stickers = witnessStickers()
        showEditorWitness(
            StickerHarness(icon = builtInRef(BuiltInSticker.NoAi), stickers = stickers),
        )
        tag("stickerBuiltIn_${BuiltInSticker.NoAi.key}").assertExists()
        captureFrame("stickerRailBuiltInApplied")
    }

    // ----- 1.2 panel ------------------------------------------------------------------------

    @Test
    fun stickerPanelOpen() = witnessFrame {
        val stickers = witnessStickers()
        showEditorWitness(StickerHarness(icon = appliedUserIcon(stickers), stickers = stickers))
        openPanel()
        tag("stickerPanelSheet").assertExists()
        captureFrame("stickerPanelOpen")
    }

    @Test
    fun stickerPanelEmptyLibrary() = witnessFrame {
        showEditorWitness(StickerHarness())
        openPanel()
        inPanel("stickerAdd").assertExists()
        captureFrame("stickerPanelEmptyLibrary")
    }

    @Test
    fun stickerPanelSelection() = witnessFrame {
        val stickers = witnessStickers()
        showEditorWitness(StickerHarness(stickers = stickers))
        if (stickers.size >= 2) {
            // Rail long-press opens the panel already in selection mode with that sticker picked.
            tag("stickerUser_${stickers[0].id}").performTouchInput { longClick() }
            awaitPanel()
            waitForIdle()
            inPanel("stickerUser_${stickers[1].id}").performClick()
            waitForIdle()
            tag("stickerSelectedCount").assertExists()
        } else {
            openPanel()
        }
        captureFrame("stickerPanelSelection")
    }

    @Test
    fun stickerPanelScrolled() = witnessFrame {
        val stickers = witnessStickers()
        showEditorWitness(StickerHarness(stickers = stickers))
        openPanel()
        val last = "stickerBuiltIn_${BuiltInSticker.entries.last().key}"
        onNode(
            hasScrollAction() and hasAnyAncestor(hasTestTag("stickerPanelSheet")),
            useUnmergedTree = true,
        ).performScrollToNode(hasTestTag(last))
        waitForIdle()
        inPanel(last).assertExists()
        captureFrame("stickerPanelScrolled")
    }

    // ----- 1.4 inspector --------------------------------------------------------------------

    @Test
    fun stickerInspector() = witnessFrame(EXPANDED_WIDTH_DP, EXPANDED_HEIGHT_DP) {
        val stickers = witnessStickers()
        showEditorWitness(
            harness = StickerHarness(icon = builtInRef(BuiltInSticker.NoAi), stickers = stickers),
            layoutClass = EditorLayoutClass.Expanded,
            widthDp = EXPANDED_WIDTH_DP,
            heightDp = EXPANDED_HEIGHT_DP,
        )
        tag("stickerInlinePanel").assertExists()
        captureFrame("stickerInspector")
    }

    @Test
    fun stickerInspectorSelection() = witnessFrame(EXPANDED_WIDTH_DP, EXPANDED_HEIGHT_DP) {
        val stickers = witnessStickers()
        showEditorWitness(
            harness = StickerHarness(stickers = stickers),
            layoutClass = EditorLayoutClass.Expanded,
            widthDp = EXPANDED_WIDTH_DP,
            heightDp = EXPANDED_HEIGHT_DP,
        )
        if (stickers.isNotEmpty()) {
            tag("stickerUser_${stickers[0].id}").performTouchInput { longClick() }
            waitForIdle()
            tag("stickerManageDeleteSelected").assertExists()
        }
        captureFrame("stickerInspectorSelection")
    }

    // ----- 1.5 canvas -----------------------------------------------------------------------

    @Test
    fun previewDragToRemoveChip() = witnessFrame(300f, 348f) {
        val photo = witnessBitmap(300, 300) {
            drawRect(Brush.linearGradient(listOf(Color(0xFFA9BCD2), Color(0xFF2E3844))))
        }
        val cell = witnessBitmap(72, 32) {
            drawRoundRect(
                color = Color(0xFFF2F4F7),
                cornerRadius = CornerRadius(4f, 4f),
            )
        }
        val config = WaterMark.default.copy(tileMode = WatermarkTileMode.CLAMP, degree = 0f)
        setContent {
            ProvideMotionPolicy(MotionPolicy.Off) {
                AppTheme {
                    CompositionLocalProvider(
                        LocalPreviewInteraction provides
                            PreviewInteraction("witness-photo", config, {}, {}),
                    ) {
                        LiveOverlayPreview(
                            OverlayPreviewChrome.LiveLayers,
                            photo,
                            overlayCellFrom(cell, config, .45f, .40f, 300),
                            null,
                            modifier = Modifier.size(width = 300.dp, height = 348.dp),
                            onOffsetCommit = { _, _ -> },
                            renderGestureCell = { cell },
                        )
                    }
                }
            }
        }
        waitForIdle()
        // Hold the drag: the chip only exists while a single watermark is in hand.
        tag("previewGestureSurface").performTouchInput {
            down(Offset(width * .5f, height * .45f))
            moveTo(Offset(width * .5f, height * .62f))
        }
        waitForIdle()
        tag("previewDragTrashZone").assertExists()
        captureL1Witness("previewDragToRemoveChip", "previewGestureSurface")
    }
}

// --------------------------------------------------------------------------------------------
// Frame harness
// --------------------------------------------------------------------------------------------

private const val PHONE_WIDTH_DP = 393f
private const val PHONE_HEIGHT_DP = 852f
private const val EXPANDED_WIDTH_DP = 1100f
private const val EXPANDED_HEIGHT_DP = 760f

/** 2 keeps hairlines (the 1 dp user-sticker outline, the 1.5 dp selected border) legible. */
private const val WITNESS_DENSITY = 2f

@OptIn(ExperimentalTestApi::class)
private fun witnessFrame(
    widthDp: Float = PHONE_WIDTH_DP,
    heightDp: Float = PHONE_HEIGHT_DP,
    block: suspend ComposeUiTest.() -> Unit,
) = runSkikoComposeUiTest(
    size = Size(widthDp * WITNESS_DENSITY, heightDp * WITNESS_DENSITY),
    density = Density(WITNESS_DENSITY),
) { this.block() }

private val witnessPhoto = ImageInfoUi(MediaRef("/tmp/ewm-witness-photo.jpg"), 1200, 1600)

private fun builtInRef(builtIn: BuiltInSticker) = MediaRef("/tmp/icon-builtin-${builtIn.key}.png")

/** The white wordmark, so the dark logo stays idle (no fill) in the frame. */
private fun appliedUserIcon(stickers: List<Sticker>): MediaRef =
    stickers.getOrNull(1)?.icon ?: MediaRef.Empty

@OptIn(ExperimentalTestApi::class)
private fun ComposeUiTest.showEditorWitness(
    harness: StickerHarness,
    layoutClass: EditorLayoutClass = EditorLayoutClass.Compact,
    widthDp: Float = PHONE_WIDTH_DP,
    heightDp: Float = PHONE_HEIGHT_DP,
) {
    warmThumbCache(harness.stickers)
    setContent {
        ProvideMotionPolicy(MotionPolicy.Off) {
            AppTheme(darkTheme = true) {
                EditorScreenUnderTest(
                    harness = harness,
                    photo = witnessPhoto,
                    modifier = Modifier.size(widthDp.dp, heightDp.dp),
                    preview = { previewModifier -> WitnessPhotoArea(previewModifier) },
                    layoutClass = layoutClass,
                )
            }
        }
    }
    waitForIdle()
}

@OptIn(ExperimentalTestApi::class)
private fun ComposeUiTest.openPanel() {
    tag("stickerExpand").performClick()
    awaitPanel()
    waitForIdle()
}

/**
 * Injected input parks the pointer where it last touched, and whatever tile has moved under it
 * draws its hover fill — which reads as a selection in a still frame. Park it over the preview
 * (no hover state there) before the dump.
 */
@OptIn(ExperimentalTestApi::class)
private fun ComposeUiTest.captureFrame(name: String) {
    tag("sharedComposeWatermarkPreview").performMouseInput { moveTo(Offset(2f, 2f)) }
    waitForIdle()
    captureL1Witness(name)
}

/** Stand-in for the live preview: a 3:4 fill, so the C1 refit above the panel is obvious. */
@Composable
private fun WitnessPhotoArea(modifier: Modifier) {
    Box(
        modifier = modifier.testTag("testEditorCanvas"),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .aspectRatio(3f / 4f)
                .background(
                    Brush.linearGradient(
                        listOf(Color(0xFFBFD0E2), Color(0xFF6E7F93), Color(0xFF2B3440)),
                    ),
                ),
        )
    }
}

// --------------------------------------------------------------------------------------------
// User sticker fixtures — real files, so the thumbnails actually decode
// --------------------------------------------------------------------------------------------

/**
 * Three PNGs with transparent backgrounds in a temp dir: a dark logo (the outline case), a white
 * wordmark and a colourful pill. Written once per process; empty when the file system is not
 * writable (iOS simulator), which just leaves the frames in their empty-library state.
 */
private val witnessStickerFiles: List<Sticker> by lazy {
    val dir = FileSystem.SYSTEM_TEMPORARY_DIRECTORY / "ewm-sticker-witness"
    val art = listOf(
        "dark-logo" to witnessBitmap(160, 160) {
            drawCircle(
                color = Color(0xFF14181D),
                radius = 68f,
                center = Offset(80f, 80f),
                style = Stroke(width = 12f),
            )
            drawRoundRect(
                color = Color(0xFF14181D),
                topLeft = Offset(56f, 46f),
                size = Size(48f, 68f),
                cornerRadius = CornerRadius(8f, 8f),
            )
        },
        "white-wordmark" to witnessBitmap(224, 80) {
            drawRoundRect(
                color = Color(0xFFF4F6F9),
                topLeft = Offset(8f, 16f),
                size = Size(132f, 18f),
                cornerRadius = CornerRadius(9f, 9f),
            )
            drawRoundRect(
                color = Color(0xFFF4F6F9),
                topLeft = Offset(8f, 46f),
                size = Size(196f, 18f),
                cornerRadius = CornerRadius(9f, 9f),
            )
            drawCircle(color = Color(0xFFF4F6F9), radius = 9f, center = Offset(158f, 25f))
        },
        "colour-pill" to witnessBitmap(200, 96) {
            drawRoundRect(
                brush = Brush.horizontalGradient(
                    listOf(Color(0xFFFF8A3D), Color(0xFFE23E8C), Color(0xFF3E7BFA)),
                ),
                topLeft = Offset(6f, 16f),
                size = Size(188f, 64f),
                cornerRadius = CornerRadius(32f, 32f),
            )
            drawCircle(color = Color.White, radius = 12f, center = Offset(48f, 48f))
        },
    )
    try {
        FileSystem.SYSTEM.createDirectories(dir)
        art.map { (id, bitmap) ->
            val path = dir / "$id.png"
            FileSystem.SYSTEM.write(path) { write(encodePng(bitmap)) }
            Sticker(id = id, icon = MediaRef(path.toString()))
        }
    } catch (_: Throwable) {
        emptyList()
    }
}

private fun witnessStickers(): List<Sticker> = witnessStickerFiles

/** Transparent-backed bitmap drawn at density 1, so draw units are pixels. */
private fun witnessBitmap(width: Int, height: Int, draw: DrawScope.() -> Unit): ImageBitmap {
    val bitmap = ImageBitmap(width, height)
    CanvasDrawScope().draw(
        Density(1f),
        LayoutDirection.Ltr,
        Canvas(bitmap),
        Size(width.toFloat(), height.toFloat()),
    ) { draw() }
    return bitmap
}

/**
 * Coil decodes off the main thread, so a fresh tile would be blank on the captured frame. Warming
 * the memory cache under the product cache key makes [ProductThumb] tiles paint from the
 * placeholder on their first composition instead.
 *
 * `setUnsafe`, not `installProductImageLoaderFactory`: another class in the same JVM may already
 * have created the singleton from Coil's defaults (no [ProductThumb] fetcher), and `setSafe`
 * throws once that has happened. Installing the product loader outright keeps a frame identical
 * whether this class runs alone or inside the whole suite.
 */
@OptIn(DelicateCoilApi::class)
private fun warmThumbCache(stickers: List<Sticker>) {
    if (stickers.isEmpty()) return
    val context = PlatformContext.INSTANCE
    val loader = buildProductImageLoader(context)
    SingletonImageLoader.setUnsafe(loader)
    runBlocking {
        stickers.forEach { sticker ->
            val thumb = ProductThumb(sticker.icon, ProductThumb.UI_THUMB_MAX_EDGE)
            loader.execute(
                ImageRequest.Builder(context)
                    .data(thumb)
                    .memoryCacheKey(productThumbCacheKey(sticker.icon.value, thumb.maxEdgePx))
                    .size(thumb.maxEdgePx)
                    .scale(Scale.FILL)
                    .precision(Precision.INEXACT)
                    .build(),
            )
        }
    }
}
