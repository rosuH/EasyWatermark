package me.rosuh.easywatermark.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.test.*
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import me.rosuh.easywatermark.data.model.*
import me.rosuh.easywatermark.render.OverlayPreviewChrome
import me.rosuh.easywatermark.ui.theme.AppTheme
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import kotlinx.coroutines.CompletableDeferred
import kotlin.math.abs
import kotlin.test.*

@OptIn(ExperimentalTestApi::class)
class PreviewGestureUiTest {
    private fun bitmap(width: Int, height: Int, color: Color): ImageBitmap {
        val bitmap = ImageBitmap(width, height)
        CanvasDrawScope().draw(Density(1f), LayoutDirection.Ltr, Canvas(bitmap), Size(width.toFloat(), height.toFloat())) { drawRect(color) }
        return bitmap
    }

    /** Photo-layer probe: the only node inside the zoom/pan layer that carries semantics. */
    private val PAN_PROBE = "ewmPanProbe"

    private fun SemanticsNodeInteractionsProvider.surfaceNode() =
        onNodeWithTag("previewGestureSurface").fetchSemanticsNode()

    private fun SemanticsNodeInteractionsProvider.layerOrigin(): Offset =
        onNodeWithContentDescription(PAN_PROBE, useUnmergedTree = true).fetchSemanticsNode().positionInRoot

    /**
     * The photo layer fills the surface and is scaled about its centre, then translated by the pan:
     * `layerOrigin = surfaceOrigin + size × (1 - zoom) / 2 + pan`.
     */
    private fun panOf(zoom: Float, surface: SemanticsNode, layer: Offset) = Offset(
        layer.x - surface.positionInRoot.x - surface.size.width * (1f - zoom) / 2f,
        layer.y - surface.positionInRoot.y - surface.size.height * (1f - zoom) / 2f,
    )

    @Test
    fun doubleTapWatermarkOpensEditor_butBlankDoubleTapDoesNot() = runComposeUiTest {
        val photo = bitmap(300, 300, Color.White)
        val cell = bitmap(60, 30, Color.Red)
        var opened = 0
        setContent {
            AppTheme {
                CompositionLocalProvider(LocalPreviewInteraction provides PreviewInteraction("photo", WaterMark.default, {}, { opened++ })) {
                    LiveOverlayPreview(OverlayPreviewChrome.LiveLayers, photo,
                        overlayCellFrom(cell, WaterMark.default.copy(tileMode = WatermarkTileMode.CLAMP), .4f, .4f, 300), null,
                        modifier = Modifier.size(width = 300.dp, height = 348.dp).testTag("gesturePane"),
                        onOffsetCommit = { _, _ -> }, renderGestureCell = { cell })
                }
            }
        }
        onNodeWithTag("previewGestureSurface").performTouchInput { doubleClick(Offset(width * .5f, height * .45f)) }
        runOnIdle { assertEquals(1, opened) }
        onNodeWithTag("previewGestureSurface").performTouchInput { doubleClick(Offset(width * .1f, height * .1f)) }
        runOnIdle { assertEquals(1, opened) }
    }

    @Test
    fun thinGlyphGapSelects_andSelectedTapEditsWithoutDoubleTapDeadline() = runComposeUiTest {
        val photo = bitmap(300, 300, Color.White)
        val cell = bitmap(60, 30, Color.Transparent)
        CanvasDrawScope().draw(Density(1f), LayoutDirection.Ltr, Canvas(cell), Size(60f, 30f)) {
            drawRect(Color.Red, Offset(31f, 16f), Size(2f, 2f))
        }
        val config = WaterMark.default.copy(tileMode = WatermarkTileMode.CLAMP)
        var opened = 0
        setContent {
            AppTheme {
                CompositionLocalProvider(LocalPreviewInteraction provides PreviewInteraction("photo", config, {}, { opened++ })) {
                    LiveOverlayPreview(OverlayPreviewChrome.LiveLayers, photo,
                        overlayCellFrom(cell, config, .4f, .4f, 300), null,
                        modifier = Modifier.size(width = 300.dp, height = 348.dp),
                        onOffsetCommit = { _, _ -> }, renderGestureCell = { cell })
                }
            }
        }
        // The tap is 1px from a thin glyph, between all 25 points in the old sampler.
        onNodeWithTag("previewGestureSurface").performTouchInput { click(Offset(width * .5f, height * .45f)) }
        onNodeWithTag("previewWatermarkTarget").assertExists()
        runOnIdle { assertEquals(0, opened) }
        onNodeWithTag("previewGestureSurface").performTouchInput {
            advanceEventTime(1200)
            click(Offset(width * .5f, height * .45f))
        }
        runOnIdle { assertEquals(1, opened) }
        onNodeWithTag("previewGestureSurface").performTouchInput { click(Offset(width * .1f, height * .1f)) }
        onNodeWithTag("previewViewTarget").assertExists()
        runOnIdle { assertEquals(1, opened) }
    }

    @Test
    fun dragLocksFirstTarget_andCommitsOnlyAtRelease() = runComposeUiTest {
        val photo = bitmap(300, 300, Color.White)
        val cell = bitmap(60, 30, Color.Red)
        var commits = 0
        var offset = Offset(.4f, .4f)
        val config = WaterMark.default.copy(tileMode = WatermarkTileMode.CLAMP)
        var overlay by mutableStateOf(overlayCellFrom(cell, config, offset.x, offset.y, 300))
        setContent {
            AppTheme {
                CompositionLocalProvider(LocalPreviewInteraction provides PreviewInteraction("photo", config, { error("drag must not change config") }, {})) {
                    LiveOverlayPreview(OverlayPreviewChrome.LiveLayers, photo, overlay, null,
                        modifier = Modifier.size(width = 300.dp, height = 348.dp).testTag("gesturePane"),
                        onOffsetCommit = { x, y -> commits++; offset = Offset(x, y); overlay = overlay.withOffset(x, y) },
                        renderGestureCell = { cell })
                }
            }
        }
        onNodeWithTag("previewGestureSurface").performTouchInput { click(Offset(width * .5f, height * .45f)) }
        onNodeWithTag("previewGestureSurface").performTouchInput {
            down(Offset(width * .5f, height * .45f))
            moveTo(Offset(width * .6f, height * .55f))
        }
        runOnIdle { assertEquals(0, commits) }
        onNodeWithTag("previewGestureSurface").performTouchInput { up() }
        waitUntil { commits == 1 }
        runOnIdle { assertTrue(offset.x > .4f && offset.y > .4f) }
        onNodeWithTag("previewSelectionDone").assertIsDisplayed().performClick()
        onNodeWithTag("previewEditText").assertDoesNotExist()
        // Starting in blank space and crossing the watermark keeps the view target.
        onNodeWithTag("previewGestureSurface").performTouchInput { swipe(Offset(width * .1f, height * .1f), Offset(width * .8f, height * .8f)) }
        runOnIdle { assertEquals(1, commits) }
    }

    @Test
    fun secondFingerDoesNotRetargetWatermark_andCommitsOneSizeRotationPair() = runComposeUiTest {
        val photo = bitmap(300, 300, Color.White)
        val cell = bitmap(60, 30, Color.Red)
        var config by mutableStateOf(WaterMark.default.copy(tileMode = WatermarkTileMode.CLAMP))
        var offset by mutableStateOf(Offset(.4f, .4f))
        val changes = mutableListOf<WatermarkConfigChange.Transform>()
        setContent {
            AppTheme {
                CompositionLocalProvider(LocalPreviewInteraction provides PreviewInteraction("photo", config, {
                    val value = it as WatermarkConfigChange.Transform
                    changes += value
                    config = config.copy(textSize = value.size, degree = value.degree)
                }, {})) {
                    LiveOverlayPreview(OverlayPreviewChrome.LiveLayers, photo,
                        overlayCellFrom(cell, config, offset.x, offset.y, 300), null,
                        modifier = Modifier.size(width = 300.dp, height = 348.dp).testTag("gesturePane"),
                        onOffsetCommit = { x, y -> offset = Offset(x, y) }, renderGestureCell = { cell })
                }
            }
        }
        onNodeWithTag("previewGestureSurface").performTouchInput { click(Offset(width * .5f, height * .45f)) }
        onNodeWithTag("previewGestureSurface").performTouchInput {
            down(0, Offset(width * .5f, height * .45f))
            down(1, Offset(width * .3f, height * .45f))
            moveTo(0, Offset(width * .6f, height * .35f))
            moveTo(1, Offset(width * .2f, height * .6f))
            up(0); up(1)
        }
        waitUntil { changes.size == 1 }
        runOnIdle { assertTrue(changes.single().size > 14f); assertNotEquals(315f, changes.single().degree) }
        onNodeWithTag("previewResetView").assertDoesNotExist()
    }

    @Test
    fun unselectedSimultaneousPointersAlwaysZoomPhoto_evenOnWatermark() = runComposeUiTest {
        val photo = bitmap(300, 300, Color.White)
        val cell = bitmap(60, 30, Color.Red)
        var config by mutableStateOf(WaterMark.default)
        var offset by mutableStateOf(Offset(0f, 0f))
        val changes = mutableListOf<WatermarkConfigChange.Transform>()
        setContent {
            AppTheme {
                CompositionLocalProvider(LocalPreviewInteraction provides PreviewInteraction("photo", config, {
                    val value = it as WatermarkConfigChange.Transform
                    changes += value
                    config = config.copy(textSize = value.size, degree = value.degree)
                }, {})) {
                    LiveOverlayPreview(OverlayPreviewChrome.LiveLayers, photo,
                        overlayCellFrom(cell, config, offset.x, offset.y, 300), null,
                        modifier = Modifier.size(width = 300.dp, height = 348.dp).testTag("gesturePane"),
                        onOffsetCommit = { x, y -> offset = Offset(x, y) }, renderGestureCell = { cell })
                }
            }
        }
        onNodeWithTag("previewGestureSurface").performTouchInput {
            down(0, Offset(width * .8f, height * .8f))
            down(1, Offset(width * .45f, height * .45f))
            moveTo(0, Offset(width * .9f, height * .9f))
            moveTo(1, Offset(width * .35f, height * .35f))
            up(0); up(1)
        }
        runOnIdle { assertTrue(changes.isEmpty()) }
        onNodeWithTag("previewViewTarget").assertExists()
        val photoBounds = onNodeWithTag("previewGestureSurface").fetchSemanticsNode().boundsInRoot
        val resetBounds = onNodeWithTag("previewResetView").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        assertTrue(resetBounds.bottom <= photoBounds.top, "reset must be outside photo")
        onNodeWithTag("previewResetView").performClick()
        onNodeWithTag("previewResetView").assertDoesNotExist()
    }

    @Test
    fun blankFirstFingerKeepsViewTarget_whenSecondFingerTouchesWatermark() = runComposeUiTest {
        val photo = bitmap(300, 300, Color.White)
        val cell = bitmap(60, 30, Color.Red)
        val config = WaterMark.default.copy(tileMode = WatermarkTileMode.CLAMP)
        setContent {
            AppTheme {
                CompositionLocalProvider(LocalPreviewInteraction provides PreviewInteraction("photo", config, { error("view changed config") }, {})) {
                    LiveOverlayPreview(OverlayPreviewChrome.LiveLayers, photo,
                        overlayCellFrom(cell, config, .4f, .4f, 300), null,
                        modifier = Modifier.size(width = 300.dp, height = 348.dp).testTag("gesturePane"),
                        onOffsetCommit = { _, _ -> error("view changed Session offset") }, renderGestureCell = { cell })
                }
            }
        }
        onNodeWithTag("previewGestureSurface").performTouchInput {
            down(0, Offset(width * .1f, height * .1f))
            // Move past touchSlop in blank space first so target locks to the view before finger 1 lands.
            moveTo(0, Offset(width * .2f, height * .2f))
            down(1, Offset(width * .5f, height * .45f))
            moveTo(0, Offset(width * .05f, height * .05f))
            moveTo(1, Offset(width * .8f, height * .8f))
            up(0); up(1)
        }
        onNodeWithTag("previewViewTarget").assertExists()
        onNodeWithTag("previewResetView").assertIsDisplayed().performClick()
        onNodeWithTag("previewResetView").assertDoesNotExist()
    }

    @Test
    fun externalConfigDuringFinalRaster_doesNotLeaveGesturesBlocked() = runComposeUiTest {
        val photo = bitmap(300, 300, Color.White)
        val cell = bitmap(60, 30, Color.Red)
        val gate = CompletableDeferred<Unit>()
        var rendering = false
        var opened = 0
        var config by mutableStateOf(WaterMark.default.copy(tileMode = WatermarkTileMode.CLAMP))
        setContent {
            AppTheme {
                CompositionLocalProvider(LocalPreviewInteraction provides PreviewInteraction("photo", config,
                    { error("superseded draft must not commit") }, { opened++ })) {
                    LiveOverlayPreview(OverlayPreviewChrome.LiveLayers, photo,
                        overlayCellFrom(cell, config, .4f, .4f, 300), null,
                        modifier = Modifier.size(width = 300.dp, height = 348.dp).testTag("gesturePane"),
                        onOffsetCommit = { _, _ -> error("superseded offset must not commit") },
                        renderGestureCell = { rendering = true; gate.await(); cell })
                }
            }
        }
        onNodeWithTag("previewGestureSurface").performTouchInput { click(Offset(width * .5f, height * .45f)) }
        onNodeWithTag("previewGestureSurface").performTouchInput {
            swipe(Offset(width * .5f, height * .45f), Offset(width * .6f, height * .55f))
        }
        waitUntil { rendering }
        runOnIdle { config = config.copy(text = "Changed elsewhere") }
        runOnIdle { gate.complete(Unit) }
        waitForIdle()
        onNodeWithTag("previewGestureSurface").performTouchInput {
            click(Offset(width * .5f, height * .45f))
        }
        waitUntil { opened == 1 }
    }

    @Test
    fun samePhotoResolutionUpgrade_keepsCommittedRepeatOffsetsInCurrentCoordinates() = runComposeUiTest {
        var photo by mutableStateOf(bitmap(300, 300, Color.White))
        var cell by mutableStateOf(bitmap(60, 30, Color.Red))
        var config by mutableStateOf(WaterMark.default)
        val changes = mutableListOf<WatermarkConfigChange.Transform>()
        setContent {
            AppTheme {
                CompositionLocalProvider(LocalPreviewInteraction provides PreviewInteraction("same-photo", config, {
                    val change = it as WatermarkConfigChange.Transform
                    changes += change
                    config = config.copy(textSize = change.size, degree = change.degree,
                        repeatOffsetX = change.repeatOffsetX!!, repeatOffsetY = change.repeatOffsetY!!)
                }, {})) {
                    LiveOverlayPreview(OverlayPreviewChrome.LiveLayers, photo,
                        overlayCellFrom(cell, config, 0f, 0f, photo.width), null,
                        modifier = Modifier.size(width = 300.dp, height = 348.dp).testTag("gesturePane"),
                        onOffsetCommit = { _, _ -> error("repeat must not change per-photo offsets") },
                        renderGestureCell = { cell })
                }
            }
        }
        runOnIdle { photo = bitmap(600, 600, Color.White); cell = bitmap(120, 60, Color.Red) }
        onNodeWithTag("previewGestureSurface").performTouchInput { click(Offset(width * .5f, height * .45f)) }
        onNodeWithTag("previewGestureSurface").performTouchInput {
            down(Offset(width * .5f, height * .45f))
            moveTo(Offset(width * .6f, height * .55f))
            up()
        }
        waitUntil { changes.size == 1 }
        runOnIdle {
            assertEquals(.1f, changes.single().repeatOffsetX!!, .001f)
            assertEquals(.1f, changes.single().repeatOffsetY!!, .001f)
        }
        onNodeWithTag("previewGestureSurface").performTouchInput {
            down(Offset(width * .5f, height * .45f))
            moveTo(Offset(width * .6f, height * .55f))
            up()
        }
        waitUntil { changes.size == 2 }
        runOnIdle { assertEquals(.2f, changes.last().repeatOffsetX!!, .001f) }
    }

    @Test
    fun stationarySelectedPinchLeavesDoneAvailable_withoutHostChange() = runComposeUiTest {
        val photo = bitmap(300, 300, Color.White)
        val cell = bitmap(60, 30, Color.Red)
        val config = WaterMark.default.copy(tileMode = WatermarkTileMode.CLAMP)
        setContent {
            AppTheme {
                CompositionLocalProvider(LocalPreviewInteraction provides PreviewInteraction("photo", config,
                    { error("stationary gesture must not change config") }, {})) {
                    LiveOverlayPreview(OverlayPreviewChrome.LiveLayers, photo,
                        overlayCellFrom(cell, config, .4f, .4f, 300), null,
                        modifier = Modifier.size(width = 300.dp, height = 348.dp),
                        onOffsetCommit = { _, _ -> }, renderGestureCell = { cell })
                }
            }
        }
        onNodeWithTag("previewGestureSurface").performTouchInput { click(Offset(width * .5f, height * .45f)) }
        onNodeWithTag("previewGestureSurface").performTouchInput {
            down(0, Offset(width * .1f, height * .1f))
            down(1, Offset(width * .8f, height * .8f))
            up(0); up(1)
        }
        waitForIdle()
        onNodeWithTag("previewSelectionDone").assertIsEnabled().performClick()
        onNodeWithTag("previewViewTarget").assertExists()
    }

    @Test
    fun changingTileModeClearsTheOldGroupSelection() = runComposeUiTest {
        val photo = bitmap(300, 300, Color.White)
        val cell = bitmap(60, 30, Color.Red)
        var config by mutableStateOf(WaterMark.default)
        setContent {
            AppTheme {
                CompositionLocalProvider(LocalPreviewInteraction provides PreviewInteraction("photo", config, {}, {})) {
                    LiveOverlayPreview(OverlayPreviewChrome.LiveLayers, photo,
                        overlayCellFrom(cell, config, .4f, .4f, 300), null,
                        modifier = Modifier.size(width = 300.dp, height = 348.dp),
                        onOffsetCommit = { _, _ -> }, renderGestureCell = { cell })
                }
            }
        }
        onNodeWithTag("previewGestureSurface").performTouchInput { click(Offset(width * .5f, height * .45f)) }
        onNodeWithTag("previewWatermarkTarget").assertExists()
        runOnIdle { config = config.copy(tileMode = WatermarkTileMode.CLAMP) }
        onNodeWithTag("previewViewTarget").assertExists()
        onNodeWithTag("previewSelectionDone").assertDoesNotExist()
    }

    @Test
    fun switchingPhotosKeepsThumbnailAndLiveViewportIdentical() = runComposeUiTest {
        var photo by mutableStateOf(bitmap(300, 300, Color.White))
        var identity by mutableStateOf("square")
        var chrome by mutableStateOf(OverlayPreviewChrome.WaitThumb)
        val cell = bitmap(60, 30, Color.Red)
        setContent {
            AppTheme {
                CompositionLocalProvider(LocalPreviewInteraction provides PreviewInteraction(identity, WaterMark.default, {}, {})) {
                    LiveOverlayPreview(chrome, photo,
                        overlayCellFrom(cell, WaterMark.default, 0f, 0f, photo.width),
                        waitThumb = { modifier ->
                            androidx.compose.foundation.Image(photo, null, modifier.testTag("waitingPhoto"),
                                contentScale = androidx.compose.ui.layout.ContentScale.Fit)
                        },
                        modifier = Modifier.size(width = 300.dp, height = 348.dp),
                        onOffsetCommit = { _, _ -> }, renderGestureCell = { cell })
                }
            }
        }
        for ((name, dimensions) in listOf("square" to (300 to 300), "portrait" to (200 to 400), "landscape" to (400 to 200))) {
            runOnIdle {
                identity = name
                photo = bitmap(dimensions.first, dimensions.second, Color.White)
                chrome = OverlayPreviewChrome.WaitThumb
            }
            val waiting = onNodeWithTag("waitingPhoto").fetchSemanticsNode().boundsInRoot
            runOnIdle { chrome = OverlayPreviewChrome.LiveLayers }
            val live = onNodeWithTag("previewGestureSurface").fetchSemanticsNode().boundsInRoot
            assertEquals(live, waiting, "$name thumbnail and live photo must fit into the same viewport")
        }
    }

    @Test
    fun resetAnimationCanBeInterrupted_andDoneKeepsPhotoView() = runComposeUiTest {
        val photo = bitmap(300, 300, Color.White)
        val cell = bitmap(60, 30, Color.Red)
        val config = WaterMark.default.copy(tileMode = WatermarkTileMode.CLAMP)
        setContent {
            AppTheme {
                CompositionLocalProvider(LocalPreviewInteraction provides PreviewInteraction("photo", config,
                    { error("view controls must not change config") }, {})) {
                    LiveOverlayPreview(OverlayPreviewChrome.LiveLayers, photo,
                        overlayCellFrom(cell, config, .4f, .4f, 300), null,
                        modifier = Modifier.size(width = 300.dp, height = 348.dp),
                        onOffsetCommit = { _, _ -> error("view controls must not change offset") },
                        renderGestureCell = { cell })
                }
            }
        }
        onNodeWithTag("previewGestureSurface").performTouchInput {
            down(0, Offset(width * .2f, height * .2f))
            down(1, Offset(width * .8f, height * .8f))
            moveTo(0, Offset(width * .05f, height * .05f))
            moveTo(1, Offset(width * .95f, height * .95f))
            up(0); up(1)
        }
        mainClock.autoAdvance = false
        onNodeWithTag("previewResetView").performClick()
        mainClock.advanceTimeBy(80)
        onNodeWithTag("previewResetView").assertIsNotEnabled()
        onNodeWithTag("previewGestureSurface").performTouchInput { down(Offset(width * .1f, height * .1f)); up() }
        mainClock.advanceTimeBy(400)
        onNodeWithTag("previewResetView").assertIsEnabled()
        onNodeWithTag("previewGestureSurface").performTouchInput { click(Offset(width * .5f, height * .45f)) }
        mainClock.advanceTimeBy(240)
        onNodeWithTag("previewSelectionDone").performClick()
        mainClock.advanceTimeBy(240)
        onNodeWithTag("previewViewTarget").assertExists()
        onNodeWithTag("previewResetView").assertIsDisplayed()
        onNodeWithTag("previewGestureSurface").performTouchInput { click(Offset(width * .5f, height * .45f)) }
        // Reset keeps the selection and never changes watermark config or Session offsets.
        onNodeWithTag("previewResetView").performClick()
        mainClock.advanceTimeBy(600)
        onNodeWithTag("previewResetView").assertDoesNotExist()
        mainClock.autoAdvance = true
        onNodeWithTag("previewWatermarkTarget").assertExists()
        val done = onNodeWithTag("previewSelectionDone").assertIsDisplayed()
        done.assertWidthIsEqualTo(48.dp)
        done.assertHeightIsEqualTo(48.dp)
        done.performClick()
        onNodeWithTag("previewViewTarget").assertExists()
    }

    @Test
    fun singleSelectionShowsDeleteAndRotate_repeatHidesThem() = runComposeUiTest {
        val photo = bitmap(300, 300, Color.White)
        val cell = bitmap(60, 30, Color.Red)
        var clamp by mutableStateOf(WaterMark.default.copy(tileMode = WatermarkTileMode.CLAMP, degree = 0f))
        var offset by mutableStateOf(Offset(.4f, .4f))
        val transforms = mutableListOf<WatermarkConfigChange.Transform>()
        setContent {
            AppTheme {
                CompositionLocalProvider(LocalPreviewInteraction provides PreviewInteraction(
                    "photo",
                    clamp,
                    { change ->
                        if (change is WatermarkConfigChange.Transform) {
                            transforms += change
                            clamp = clamp.copy(textSize = change.size, degree = change.degree)
                        }
                    },
                    {},
                )) {
                    LiveOverlayPreview(
                        OverlayPreviewChrome.LiveLayers,
                        photo,
                        overlayCellFrom(cell, clamp, offset.x, offset.y, 300),
                        null,
                        modifier = Modifier.size(width = 300.dp, height = 348.dp),
                        onOffsetCommit = { x, y -> offset = Offset(x, y) },
                        renderGestureCell = { cell },
                    )
                }
            }
        }
        onNodeWithTag("previewSelectionDelete").assertDoesNotExist()
        onNodeWithTag("previewSelectionRotate").assertDoesNotExist()
        onNodeWithTag("previewGestureSurface").performTouchInput { click(Offset(width * .5f, height * .45f)) }
        onNodeWithTag("previewSelectionDelete").assertDoesNotExist()
        onNodeWithTag("previewSelectionRotate").assertIsDisplayed().performTouchInput {
            down(center)
            moveBy(Offset(0f, 48f))
            up()
        }
        waitUntil { transforms.size == 1 }
        runOnIdle { assertNotEquals(0f, clamp.degree) }
        runOnIdle { clamp = clamp.copy(tileMode = WatermarkTileMode.REPEAT) }
        onNodeWithTag("previewSelectionDelete").assertDoesNotExist()
        onNodeWithTag("previewSelectionRotate").assertDoesNotExist()
        onNodeWithTag("previewViewTarget").assertExists()
    }

    @Test
    fun repeatSelectionKeepsTheDashedFrameWithoutHandles() = runComposeUiTest {
        val photo = bitmap(300, 300, Color.White)
        val cell = bitmap(60, 30, Color.Red)
        val config = WaterMark.default.copy(tileMode = WatermarkTileMode.REPEAT)
        setContent {
            AppTheme {
                CompositionLocalProvider(LocalPreviewInteraction provides PreviewInteraction("photo", config, {}, {})) {
                    LiveOverlayPreview(
                        OverlayPreviewChrome.LiveLayers,
                        photo,
                        overlayCellFrom(cell, config, .4f, .4f, 300),
                        null,
                        modifier = Modifier.size(width = 300.dp, height = 348.dp),
                        onOffsetCommit = { _, _ -> },
                        renderGestureCell = { cell },
                    )
                }
            }
        }
        onNodeWithTag("previewGestureSurface").performTouchInput { click(Offset(width * .5f, height * .45f)) }
        onNodeWithTag("previewWatermarkTarget").assertExists()
        onNodeWithTag("previewSelectionDelete").assertDoesNotExist()
        onNodeWithTag("previewSelectionRotate").assertDoesNotExist()
    }

    @Test
    fun inverseViewCoordinatesAndRepeatPhaseRemainConsistent() {
        val fit = computeFittedImageRect(400f, 600f, 800f, 400f)!!
        assertEquals(Offset(.5f, .5f), photoPoint(Offset(240f, 320f), Size(400f, 600f), fit, 2f, Offset(40f, 20f)))
        assertNull(previewCellHit(Offset(-1f, 1f), Size(100f, 100f), Size(20f, 10f), Offset.Zero, true))
        val hit = previewCellHit(Offset(3f, 3f), Size(100f, 100f), Size(20f, 10f), Offset(10f, 5f), true)!!
        assertEquals(Offset(-10f, -5f), hit.origin)
        assertEquals(-1, hit.column)
        assertEquals(-1, hit.row)
        assertEquals(Offset.Zero, boundedViewPan(Offset(20f, 30f), 1f, fit))
    }

    @Test
    fun rebasedViewPan_scalesWithTheFitAndStaysInsideTheNewBounds() {
        // 300 x 300 surface vs 300 x 200 surface, square photo: the fit goes 300 -> 200.
        val tall = computeFittedImageRect(300f, 300f, 300f, 300f)!!
        val short = computeFittedImageRect(300f, 200f, 300f, 300f)!!
        // Shrinking keeps the centred photo point: pan scales by 200/300, bounds go ±150 -> ±100.
        assertEquals(Offset(40f, 20f), rebasedViewPan(Offset(60f, 30f), 2f, tall, short))
        // A pan sitting on the old bound lands on the new bound, not past it.
        assertEquals(Offset(100f, -100f), rebasedViewPan(Offset(150f, -150f), 2f, tall, short))
        // Growing takes the same photo point back out.
        assertEquals(Offset(150f, -150f), rebasedViewPan(Offset(100f, -100f), 2f, short, tall))
        // Anything over the new bound is clamped, not merely scaled.
        assertEquals(Offset(100f, 100f), rebasedViewPan(Offset(900f, 900f), 2f, tall, short))
        // Unzoomed: no pan at all.
        assertEquals(Offset.Zero, rebasedViewPan(Offset(80f, 80f), 1f, tall, short))
        // Same fit in, same pan out.
        assertEquals(Offset(40f, 20f), rebasedViewPan(Offset(40f, 20f), 2f, tall, tall))
        // No fit (surface not laid out yet): nothing to rebase onto.
        assertEquals(Offset.Zero, rebasedViewPan(Offset(40f, 20f), 2f, null, short))
        assertEquals(Offset.Zero, rebasedViewPan(Offset(40f, 20f), 2f, tall, null))
    }

    /**
     * The sticker panel refits the preview while it opens: a zoomed, panned view must rebase
     * instead of keeping pixel pan that no longer fits the smaller image (blank band).
     */
    @Test
    fun shrinkingTheSurfaceRebasesViewPanInsideTheNewBounds() = runComposeUiTest {
        val photo = bitmap(300, 300, Color.White)
        val cell = bitmap(60, 30, Color.Red)
        // REPEAT + nothing selected: every gesture targets the view, never the watermark.
        val config = WaterMark.default
        var surfaceHeight by mutableStateOf(348.dp)
        setContent {
            AppTheme {
                CompositionLocalProvider(LocalPreviewInteraction provides PreviewInteraction("photo", config, {}, {})) {
                    LiveOverlayPreview(OverlayPreviewChrome.LiveLayers, photo,
                        overlayCellFrom(cell, config, .4f, .4f, 300), null,
                        modifier = Modifier.size(width = 300.dp, height = surfaceHeight),
                        contentDescription = PAN_PROBE,
                        onOffsetCommit = { _, _ -> }, renderGestureCell = { cell })
                }
            }
        }
        // Pinch about the surface centre in one symmetric event: pan stays zero, so the photo
        // layer's origin gives the zoom away.
        onNodeWithTag("previewGestureSurface").performTouchInput {
            val mid = Offset(width / 2f, height / 2f)
            down(0, mid - Offset(30f, 0f))
            down(1, mid + Offset(30f, 0f))
            updatePointerTo(0, mid - Offset(90f, 0f))
            updatePointerTo(1, mid + Offset(90f, 0f))
            move()
            up(0)
            up(1)
        }
        waitForIdle()
        val zoomedSurface = surfaceNode()
        val zoom = 1f - 2f * (layerOrigin().x - zoomedSurface.positionInRoot.x) / zoomedSurface.size.width
        assertTrue(zoom > 1.5f, "the centred pinch must zoom the view in, got $zoom")
        // Zoom came off the x axis, so the y axis is the check that the pan really stayed zero.
        val centredPan = panOf(zoom, zoomedSurface, layerOrigin())
        assertTrue(
            abs(centredPan.x) < 1f && abs(centredPan.y) < 1f,
            "a centred pinch must not pan, got $centredPan",
        )

        // Pan hard into the bottom-right corner: the pan ends clamped on the bound.
        onNodeWithTag("previewGestureSurface").performTouchInput {
            down(Offset(width / 2f, height / 2f))
            moveBy(Offset(600f, 600f))
            moveBy(Offset(600f, 600f))
            up()
        }
        waitForIdle()
        val beforeSurface = surfaceNode()
        val beforeFit = computeFittedImageRect(
            beforeSurface.size.width.toFloat(), beforeSurface.size.height.toFloat(), 300f, 300f,
        )!!
        val beforePan = panOf(zoom, beforeSurface, layerOrigin())
        assertEquals(
            beforeFit.width * (zoom - 1f) / 2f, beforePan.x, 1f,
            "the drag must park the pan on the bound",
        )

        // The panel refit: the surface gets shorter, so the fitted image does too.
        runOnIdle { surfaceHeight = 248.dp }
        waitForIdle()
        val afterSurface = surfaceNode()
        val afterFit = computeFittedImageRect(
            afterSurface.size.width.toFloat(), afterSurface.size.height.toFloat(), 300f, 300f,
        )!!
        assertTrue(afterFit.width < beforeFit.width, "the surface must actually shrink")
        val afterPan = panOf(zoom, afterSurface, layerOrigin())
        val boundX = afterFit.width * (zoom - 1f) / 2f
        val boundY = afterFit.height * (zoom - 1f) / 2f
        assertTrue(
            abs(afterPan.x) <= boundX + 1f && abs(afterPan.y) <= boundY + 1f,
            "pan $afterPan must stay inside the new bounds (±$boundX, ±$boundY)",
        )
        // Same photo point under the surface centre: pan scaled with the fitted image.
        assertEquals(beforePan.x * afterFit.width / beforeFit.width, afterPan.x, 1.5f)
        assertEquals(beforePan.y * afterFit.height / beforeFit.height, afterPan.y, 1.5f)
    }

    @Test
    fun computeRotatedSelectionFrame_rotatesCornersClockwiseAndRespectsMinSize() {
        val center = Offset(200f, 200f)
        val f0 = computeRotatedSelectionFrame(center, 80f, 40f, framePadPx = 8f, minSizePx = 32f, degree = 0f)
        assertEquals(48f, f0.halfW, 0.01f)
        assertEquals(28f, f0.halfH, 0.01f)
        assertEquals(Offset(248f, 172f), f0.topRightCanvas)
        assertEquals(Offset(248f, 228f), f0.bottomRightCanvas)

        // +90° clockwise: local topRight (+halfW, -halfH) rotates to (+halfH, +halfW).
        val f90 = computeRotatedSelectionFrame(center, 80f, 40f, framePadPx = 8f, minSizePx = 32f, degree = 90f)
        assertEquals(228f, f90.topRightCanvas.x, 0.1f)
        assertEquals(248f, f90.topRightCanvas.y, 0.1f)
        assertEquals(172f, f90.bottomRightCanvas.x, 0.1f)
        assertEquals(248f, f90.bottomRightCanvas.y, 0.1f)

        // Tiny content clamps cleanly to minSizePx/2 without collapsing.
        val fTiny = computeRotatedSelectionFrame(center, 4f, 4f, framePadPx = 2f, minSizePx = 32f, degree = -30f)
        assertEquals(16f, fTiny.halfW, 0.01f)
        assertEquals(16f, fTiny.halfH, 0.01f)
        assertEquals(-30f, fTiny.degree, 0.01f)
    }

    @Test
    fun rotateHandleShowsAngleHudAndShiftsCornerHandles() = runComposeUiTest {
        val photo = bitmap(300, 300, Color.White)
        val cell = bitmap(80, 40, Color.Red)
        var mark by mutableStateOf(WaterMark.default.copy(tileMode = WatermarkTileMode.CLAMP, degree = 0f))
        var offset by mutableStateOf(Offset(.35f, .4f))
        val transforms = mutableListOf<WatermarkConfigChange.Transform>()
        setContent {
            AppTheme {
                CompositionLocalProvider(LocalPreviewInteraction provides PreviewInteraction(
                    "photo",
                    mark,
                    { change ->
                        if (change is WatermarkConfigChange.Transform) {
                            transforms += change
                            mark = mark.copy(textSize = change.size, degree = change.degree)
                        }
                    },
                    {},
                )) {
                    LiveOverlayPreview(
                        OverlayPreviewChrome.LiveLayers,
                        photo,
                        overlayCellFrom(cell, mark, offset.x, offset.y, 300),
                        null,
                        modifier = Modifier.size(width = 300.dp, height = 348.dp),
                        onOffsetCommit = { x, y -> offset = Offset(x, y) },
                        renderGestureCell = { cell },
                    )
                }
            }
        }
        onNodeWithTag("previewGestureSurface").performTouchInput { click(Offset(width * .48f, height * .46f)) }
        onNodeWithTag("previewSelectionDelete").assertDoesNotExist()
        val beforeRotateBounds = onNodeWithTag("previewSelectionRotate").assertIsDisplayed().fetchSemanticsNode().boundsInRoot

        onNodeWithTag("previewSelectionAngle").assertDoesNotExist()
        onNodeWithTag("previewSelectionRotate").performTouchInput {
            down(center)
            moveBy(Offset(-16f, 16f))
            moveBy(Offset(-16f, 16f))
            // Keep finger down so live angle HUD stays visible.
        }
        onNodeWithTag("previewSelectionAngle").assertIsDisplayed()
            .assertTextContains("%", substring = true)
            .assertTextContains("°", substring = true)
        onNodeWithTag("previewSelectionRotate").performTouchInput { up() }
        waitUntil { transforms.size == 1 }
        runOnIdle { assertNotEquals(0f, mark.degree) }

        val afterRotateBounds = onNodeWithTag("previewSelectionRotate").fetchSemanticsNode().boundsInRoot
        assertTrue(
            beforeRotateBounds.topLeft != afterRotateBounds.topLeft,
            "Rotated selection frame handle should move with the rotated corner vertex",
        )
    }

    @Test
    fun clampDragAllowsCornerPlacementPastCellPadding() = runComposeUiTest {
        val photo = bitmap(300, 300, Color.White)
        val cell = bitmap(60, 60, Color.Red)
        var offset = Offset(.4f, .4f)
        val config = WaterMark.default.copy(tileMode = WatermarkTileMode.CLAMP, degree = 0f)
        var overlay by mutableStateOf(overlayCellFrom(cell, config, offset.x, offset.y, 300))
        var commits = 0
        setContent {
            AppTheme {
                CompositionLocalProvider(LocalPreviewInteraction provides PreviewInteraction("photo", config, {}, {})) {
                    LiveOverlayPreview(
                        OverlayPreviewChrome.LiveLayers,
                        photo,
                        overlay,
                        null,
                        modifier = Modifier.size(width = 300.dp, height = 348.dp),
                        onOffsetCommit = { x, y ->
                            commits += 1
                            offset = Offset(x, y)
                            overlay = overlay.withOffset(x, y)
                        },
                        renderGestureCell = { cell },
                    )
                }
            }
        }
        onNodeWithTag("previewGestureSurface").performTouchInput { click(Offset(width * .5f, height * .5f)) }
        onNodeWithTag("previewWatermarkTarget").assertExists()

        // Drag into top-left corner past the outer cell padding.
        onNodeWithTag("previewGestureSurface").performTouchInput {
            down(Offset(width * .5f, height * .5f))
            moveTo(Offset(-width * .05f, -height * .05f))
            up()
        }
        waitUntil { commits == 1 }
        waitForIdle()
        runOnIdle {
            // keepInside = 12px on 60x60 mark => center clamped at (-18, -18), offset = -48 / 300 = -0.16f.
            assertEquals(-0.16f, offset.x, 0.02f)
            assertEquals(-0.16f, offset.y, 0.02f)
        }

        // Deselect and verify the remaining 12px top-left corner sliver can still be tapped & selected.
        onNodeWithTag("previewGestureSurface").performTouchInput { click(Offset(width * .9f, height * .9f)) }
        onNodeWithTag("previewViewTarget").assertExists()
        onNodeWithTag("previewGestureSurface").performTouchInput { click(Offset(width * .02f, height * .02f)) }
        onNodeWithTag("previewWatermarkTarget").assertExists()

        // Drag from center to bottom-right corner past (1 - cell/photo).
        runOnIdle {
            offset = Offset(.4f, .4f)
            overlay = overlayCellFrom(cell, config, offset.x, offset.y, 300)
        }
        onNodeWithTag("previewGestureSurface").performTouchInput { click(Offset(width * .9f, height * .9f)) }
        onNodeWithTag("previewGestureSurface").performTouchInput { click(Offset(width * .5f, height * .5f)) }
        onNodeWithTag("previewWatermarkTarget").assertExists()
        onNodeWithTag("previewGestureSurface").performTouchInput {
            down(Offset(width * .5f, height * .5f))
            moveTo(Offset(width * 1.5f, height * 1.5f))
            up()
        }
        waitUntil { commits == 2 }
        waitForIdle()
        runOnIdle {
            // center clamped at (318, 318), offset = (318 - 30) / 300 = 0.96f.
            assertEquals(0.96f, offset.x, 0.02f)
            assertEquals(0.96f, offset.y, 0.02f)
        }

        onNodeWithTag("previewGestureSurface").performTouchInput { click(Offset(width * .1f, height * .1f)) }
        onNodeWithTag("previewViewTarget").assertExists()
        onNodeWithTag("previewGestureSurface").performTouchInput { click(Offset(width * .98f, height * .98f)) }
        onNodeWithTag("previewWatermarkTarget").assertExists()
    }

    @Test
    fun clampSingleWatermarkCenter_allowsDeepEdgeOverhangAndReversesWithoutSticking() = runComposeUiTest {
        val photoSize = IntSize(300, 300)
        // 0°: 120x120 mark -> keepInside = 12 -> center can reach -48, leaving 12px inside photo.
        val c0 = clampSingleWatermarkCenter(Offset(-200f, 150f), photoSize, 120f, 120f, 0f)
        assertEquals(-48f, c0.x, 0.1f)
        assertEquals(150f, c0.y, 0.1f)

        // 45° rotated 120x120 mark at top-left corner: diamond tip extends well past center < 0.
        val c45 = clampSingleWatermarkCenter(Offset(-200f, -200f), photoSize, 120f, 120f, 45f)
        assertTrue(c45.x < -25f && c45.y < -25f, "45° rotated mark must overhang corner smoothly (got $c45)")

        // End-to-end continuous drag: push past left edge, then immediately reverse right by +30px
        // without lifting finger; reversing must respond immediately with zero overshoot dead-zone.
        val photo = bitmap(300, 300, Color.White)
        val cell = bitmap(120, 120, Color.Red)
        var offset = Offset(0.3f, 0.3f)
        val config = WaterMark.default.copy(tileMode = WatermarkTileMode.CLAMP, degree = 0f)
        var overlay by mutableStateOf(overlayCellFrom(cell, config, offset.x, offset.y, 300))
        var commits = 0
        setContent {
            AppTheme {
                CompositionLocalProvider(LocalPreviewInteraction provides PreviewInteraction("photo", config, {}, {})) {
                    LiveOverlayPreview(
                        OverlayPreviewChrome.LiveLayers,
                        photo,
                        overlay,
                        null,
                        modifier = Modifier.size(width = 300.dp, height = 348.dp),
                        onOffsetCommit = { x, y ->
                            commits += 1
                            offset = Offset(x, y)
                            overlay = overlay.withOffset(x, y)
                        },
                        renderGestureCell = { cell },
                    )
                }
            }
        }
        onNodeWithTag("previewGestureSurface").performTouchInput { click(Offset(width * .5f, height * .5f)) }
        onNodeWithTag("previewWatermarkTarget").assertExists()

        // Drag far left (center wants -300, clamps at -48 -> offsetX = -108/300 = -0.36f),
        // then immediately reverse +30px right (clamped center -48 -> -18 -> offsetX = -78/300 = -0.26f).
        onNodeWithTag("previewGestureSurface").performTouchInput {
            down(Offset(width * .5f, height * .5f))
            moveTo(Offset(-width * 1.0f, height * .5f))
            moveBy(Offset(30f, 0f))
            up()
        }
        waitUntil { commits == 1 }
        waitForIdle()
        runOnIdle {
            assertTrue(
                offset.x > -0.30f && offset.x < -0.22f,
                "reversing +30px from clamped edge must move immediately without dead-zone (offsetX=${offset.x})",
            )
        }
    }

    @Test
    fun emptyOverlayClearsSelectionAndIgnoresTaps() = runComposeUiTest {
        val photo = bitmap(300, 300, Color.White)
        val cell = bitmap(60, 60, Color.Red)
        var config by mutableStateOf(
            WaterMark.default.copy(
                markMode = WatermarkMode.Image,
                tileMode = WatermarkTileMode.CLAMP,
                iconUri = MediaRef("/tmp/icon.png"),
            ),
        )
        var overlay by mutableStateOf(overlayCellFrom(cell, config, .4f, .4f, 300))
        setContent {
            AppTheme {
                CompositionLocalProvider(LocalPreviewInteraction provides PreviewInteraction("photo", config, {}, {})) {
                    LiveOverlayPreview(
                        OverlayPreviewChrome.LiveLayers,
                        photo,
                        overlay,
                        null,
                        modifier = Modifier.size(width = 300.dp, height = 348.dp),
                        onOffsetCommit = { _, _ -> },
                        renderGestureCell = { cell },
                    )
                }
            }
        }
        onNodeWithTag("previewGestureSurface").performTouchInput { click(Offset(width * .5f, height * .5f)) }
        onNodeWithTag("previewWatermarkTarget").assertExists()
        onNodeWithTag("previewSelectionDelete").assertDoesNotExist()
        onNodeWithTag("previewSelectionRotate").assertIsDisplayed()

        runOnIdle {
            val cleared = config.copy(iconUri = MediaRef.Empty)
            config = cleared
            overlay = emptyOverlayCellFor(cleared, 300)
        }
        onNodeWithTag("previewViewTarget").assertExists()
        onNodeWithTag("previewSelectionDelete").assertDoesNotExist()
        onNodeWithTag("previewSelectionRotate").assertDoesNotExist()

        onNodeWithTag("previewGestureSurface").performTouchInput { click(Offset(width * .5f, height * .5f)) }
        onNodeWithTag("previewViewTarget").assertExists()
        onNodeWithTag("previewWatermarkTarget").assertDoesNotExist()
    }

    @Test
    fun hitSingleWatermark_handlesHollowInteriorRotatedFrameAndCornerSlivers() {
        val photo = Size(300f, 300f)
        val center = Offset(150f, 150f)
        assertTrue(hitSingleWatermark(center, photo, center, 100f, 40f, degree = 0f, minHalfSize = 16f, slop = 12f))
        assertTrue(hitSingleWatermark(Offset(88f, 118f), photo, center, 100f, 40f, degree = 0f, minHalfSize = 16f, slop = 12f))
        assertFalse(hitSingleWatermark(Offset(87f, 150f), photo, center, 100f, 40f, degree = 0f, minHalfSize = 16f, slop = 12f))

        // Rotated 90 degrees: long axis runs vertically, short axis horizontally.
        assertTrue(hitSingleWatermark(Offset(150f, 200f), photo, center, 100f, 40f, degree = 90f, minHalfSize = 16f, slop = 12f))
        assertFalse(hitSingleWatermark(Offset(200f, 150f), photo, center, 100f, 40f, degree = 90f, minHalfSize = 16f, slop = 12f))

        // Tiny content respects minHalfSize floor.
        assertTrue(hitSingleWatermark(Offset(170f, 150f), photo, center, 2f, 2f, degree = 0f, minHalfSize = 16f, slop = 12f))

        // Corner sliver overhanging outside the photo remains tappable inside photo + slop.
        val cornerCenter = Offset(-18f, -18f)
        assertTrue(hitSingleWatermark(Offset(6f, 6f), photo, cornerCenter, 60f, 60f, degree = 0f, minHalfSize = 16f, slop = 12f))
        assertFalse(hitSingleWatermark(Offset(-20f, -20f), photo, cornerCenter, 60f, 60f, degree = 0f, minHalfSize = 16f, slop = 12f))
        assertFalse(hitSingleWatermark(Offset(Float.NaN, 10f), photo, center, 60f, 60f, degree = 0f, minHalfSize = 16f, slop = 12f))
        assertFalse(hitSingleWatermark(center, Size.Zero, center, 60f, 60f, degree = 0f, minHalfSize = 16f, slop = 12f))
    }

    @Test
    fun snapSingleWatermarkCenter_and_snapWatermarkDegree_enforceHysteresis() {
        val photoSize = IntSize(300, 300)
        val outside = snapSingleWatermarkCenter(Offset(144f, 160f), photoSize, wasSnappedX = false, wasSnappedY = false, enterThresholdPx = 5f, exitThresholdPx = 9f)
        assertEquals(Offset(144f, 160f), outside.center)
        assertFalse(outside.snappedX)
        assertFalse(outside.snappedY)

        val entered = snapSingleWatermarkCenter(Offset(146f, 154f), photoSize, wasSnappedX = false, wasSnappedY = false, enterThresholdPx = 5f, exitThresholdPx = 9f)
        assertEquals(Offset(150f, 150f), entered.center)
        assertTrue(entered.snappedX)
        assertTrue(entered.snappedY)

        val held = snapSingleWatermarkCenter(Offset(158f, 142f), photoSize, wasSnappedX = true, wasSnappedY = true, enterThresholdPx = 5f, exitThresholdPx = 9f)
        assertEquals(Offset(150f, 150f), held.center)
        assertTrue(held.snappedX)
        assertTrue(held.snappedY)

        val exited = snapSingleWatermarkCenter(Offset(160f, 140f), photoSize, wasSnappedX = true, wasSnappedY = true, enterThresholdPx = 5f, exitThresholdPx = 9f)
        assertEquals(Offset(160f, 140f), exited.center)
        assertFalse(exited.snappedX)
        assertFalse(exited.snappedY)

        val xOnly = snapSingleWatermarkCenter(Offset(152f, 100f), photoSize, wasSnappedX = false, wasSnappedY = false, enterThresholdPx = 5f, exitThresholdPx = 9f)
        assertEquals(Offset(150f, 100f), xOnly.center)
        assertTrue(xOnly.snappedX)
        assertFalse(xOnly.snappedY)

        // Angle snap across 0/360 wrap and cardinals with enter=3.5, exit=6.0.
        val wrapEnter = snapWatermarkDegree(357f, wasSnapped = false)
        assertTrue(wrapEnter.snapped)
        assertEquals(0f, wrapEnter.degree, 0.001f)
        assertEquals(0f, wrapEnter.targetDegree)

        val wrapHold = snapWatermarkDegree(355f, wasSnapped = true)
        assertTrue(wrapHold.snapped)
        assertEquals(0f, wrapHold.degree, 0.001f)

        val wrapExit = snapWatermarkDegree(353f, wasSnapped = true)
        assertFalse(wrapExit.snapped)
        assertEquals(353f, wrapExit.degree, 0.001f)
        assertNull(wrapExit.targetDegree)

        val deg90Enter = snapWatermarkDegree(87f, wasSnapped = false)
        assertTrue(deg90Enter.snapped)
        assertEquals(90f, deg90Enter.degree, 0.001f)

        val deg90Hold = snapWatermarkDegree(95f, wasSnapped = true)
        assertTrue(deg90Hold.snapped)
        assertEquals(90f, deg90Hold.degree, 0.001f)

        val deg180 = snapWatermarkDegree(182f, wasSnapped = false)
        assertTrue(deg180.snapped)
        assertEquals(180f, deg180.degree, 0.001f)

        val negDeg270 = snapWatermarkDegree(-92f, wasSnapped = false)
        assertTrue(negDeg270.snapped)
        assertEquals(270f, negDeg270.degree, 0.001f)
    }

    @Test
    fun isInBottomTrashZone_checksCircularRadiusAtBottomCenter() {
        val box = IntSize(300, 300)
        assertTrue(isInBottomTrashZone(Offset(150f, 260f), box, zoneCenterYOffsetPx = 40f, hitRadiusPx = 42f))
        assertTrue(isInBottomTrashZone(Offset(150f, 219f), box, zoneCenterYOffsetPx = 40f, hitRadiusPx = 42f))
        assertFalse(isInBottomTrashZone(Offset(150f, 217f), box, zoneCenterYOffsetPx = 40f, hitRadiusPx = 42f))
        assertFalse(isInBottomTrashZone(Offset(150f, 260f), IntSize.Zero, zoneCenterYOffsetPx = 40f, hitRadiusPx = 42f))
    }

    @Test
    fun hollowIcon_selectsViaInteriorTap_and_unselectedDirectDragAndPinchLeaveCanvasClean() = runComposeUiTest {
        val photo = bitmap(300, 300, Color.White)
        val cell = bitmap(60, 60, Color.Transparent)
        CanvasDrawScope().draw(Density(1f), LayoutDirection.Ltr, Canvas(cell), Size(60f, 60f)) {
            drawRect(Color.Red, Offset(0f, 0f), Size(60f, 2f))
            drawRect(Color.Red, Offset(0f, 58f), Size(60f, 2f))
            drawRect(Color.Red, Offset(0f, 0f), Size(2f, 60f))
            drawRect(Color.Red, Offset(58f, 0f), Size(2f, 60f))
        }
        var config by mutableStateOf(
            WaterMark.default.copy(
                markMode = WatermarkMode.Image,
                tileMode = WatermarkTileMode.CLAMP,
                iconUri = MediaRef("/tmp/hollow.png"),
                degree = 0f,
            ),
        )
        var offset by mutableStateOf(Offset(.4f, .4f))
        var overlay by mutableStateOf(overlayCellFrom(cell, config, offset.x, offset.y, 300))
        var commits = 0
        val transforms = mutableListOf<WatermarkConfigChange.Transform>()
        setContent {
            AppTheme {
                CompositionLocalProvider(LocalPreviewInteraction provides PreviewInteraction("photo", config, {
                    val change = it as WatermarkConfigChange.Transform
                    transforms += change
                    config = config.copy(textSize = change.size, degree = change.degree)
                    overlay = overlayCellFrom(cell, config, offset.x, offset.y, 300)
                }, {})) {
                    LiveOverlayPreview(
                        OverlayPreviewChrome.LiveLayers,
                        photo,
                        overlay,
                        null,
                        modifier = Modifier.size(width = 300.dp, height = 348.dp),
                        onOffsetCommit = { x, y ->
                            commits++
                            offset = Offset(x, y)
                            overlay = overlay.withOffset(x, y)
                        },
                        renderGestureCell = { cell },
                    )
                }
            }
        }
        // Tapping the hollow transparent center selects the sticker.
        onNodeWithTag("previewGestureSurface").performTouchInput { click(Offset(width * .5f, height * .5f)) }
        onNodeWithTag("previewWatermarkTarget").assertExists()
        onNodeWithTag("previewSelectionRotate").assertIsDisplayed()

        // Deselect back to clean view.
        onNodeWithTag("previewGestureSurface").performTouchInput { click(Offset(width * .1f, height * .1f)) }
        onNodeWithTag("previewViewTarget").assertExists()

        // Unselected direct drag works immediately and keeps canvas IG-clean on release.
        onNodeWithTag("previewGestureSurface").performTouchInput {
            down(Offset(width * .5f, height * .5f))
            moveTo(Offset(width * .7f, height * .7f))
            up()
        }
        waitUntil { commits == 1 }
        runOnIdle { assertTrue(offset.x > .55f && offset.y > .55f) }
        onNodeWithTag("previewViewTarget").assertExists()
        onNodeWithTag("previewSelectionDone").assertDoesNotExist()
        onNodeWithTag("previewSelectionRotate").assertDoesNotExist()

        // Unselected direct 2-finger pinch (one finger on sticker hollow center) transforms cleanly and leaves canvas clean.
        onNodeWithTag("previewGestureSurface").performTouchInput {
            down(0, Offset(width * .7f, height * .7f))
            down(1, Offset(width * .2f, height * .2f))
            moveTo(0, Offset(width * .85f, height * .65f))
            moveTo(1, Offset(width * .1f, height * .1f))
            up(0); up(1)
        }
        waitUntil { transforms.size == 1 }
        runOnIdle {
            assertTrue(transforms.single().size > 14f)
            assertNotEquals(0f, transforms.single().degree)
        }
        onNodeWithTag("previewViewTarget").assertExists()
        onNodeWithTag("previewResetView").assertDoesNotExist()
        onNodeWithTag("previewSelectionRotate").assertDoesNotExist()
    }

    @Test
    fun staggeredTwoFingerPinchWithSecondFingerOnSticker_grabsStickerBeforeMove() = runComposeUiTest {
        val photo = bitmap(300, 300, Color.White)
        val cell = bitmap(60, 30, Color.Red)
        var config by mutableStateOf(WaterMark.default.copy(tileMode = WatermarkTileMode.CLAMP))
        var offset by mutableStateOf(Offset(.4f, .4f))
        val changes = mutableListOf<WatermarkConfigChange.Transform>()
        setContent {
            AppTheme {
                CompositionLocalProvider(LocalPreviewInteraction provides PreviewInteraction("photo", config, {
                    val value = it as WatermarkConfigChange.Transform
                    changes += value
                    config = config.copy(textSize = value.size, degree = value.degree)
                }, {})) {
                    LiveOverlayPreview(
                        OverlayPreviewChrome.LiveLayers,
                        photo,
                        overlayCellFrom(cell, config, offset.x, offset.y, 300),
                        null,
                        modifier = Modifier.size(width = 300.dp, height = 348.dp),
                        onOffsetCommit = { x, y -> offset = Offset(x, y) },
                        renderGestureCell = { cell },
                    )
                }
            }
        }
        onNodeWithTag("previewGestureSurface").performTouchInput {
            down(0, Offset(width * .1f, height * .1f))
            advanceEventTime(16)
            down(1, Offset(width * .5f, height * .45f))
            moveTo(0, Offset(width * .05f, height * .05f))
            moveTo(1, Offset(width * .7f, height * .75f))
            up(0); up(1)
        }
        waitUntil { changes.size == 1 }
        runOnIdle {
            assertTrue(changes.single().size > 14f)
            assertNotEquals(315f, changes.single().degree)
        }
        onNodeWithTag("previewResetView").assertDoesNotExist()
        onNodeWithTag("previewViewTarget").assertExists()
    }

    @Test
    fun selectedSingleBlankPinch_zoomsPhotoWithoutChangingWatermark() = runComposeUiTest {
        val photo = bitmap(300, 300, Color.White)
        val cell = bitmap(60, 30, Color.Red)
        val config = WaterMark.default.copy(tileMode = WatermarkTileMode.CLAMP)
        val changes = mutableListOf<WatermarkConfigChange>()
        var commits = 0
        setContent {
            AppTheme {
                CompositionLocalProvider(LocalPreviewInteraction provides PreviewInteraction("photo", config, {
                    changes += it
                }, {})) {
                    LiveOverlayPreview(
                        OverlayPreviewChrome.LiveLayers,
                        photo,
                        overlayCellFrom(cell, config, .4f, .4f, 300),
                        null,
                        modifier = Modifier.size(width = 300.dp, height = 348.dp),
                        onOffsetCommit = { _, _ -> commits++ },
                        renderGestureCell = { cell },
                    )
                }
            }
        }
        onNodeWithTag("previewGestureSurface").performTouchInput { click(Offset(width * .5f, height * .45f)) }
        onNodeWithTag("previewWatermarkTarget").assertExists()
        onNodeWithTag("previewGestureSurface").performTouchInput {
            down(0, Offset(width * .1f, height * .1f))
            down(1, Offset(width * .3f, height * .1f))
            moveTo(0, Offset(width * .05f, height * .05f))
            moveTo(1, Offset(width * .4f, height * .2f))
            up(0); up(1)
        }
        runOnIdle {
            assertTrue(changes.isEmpty())
            assertEquals(0, commits)
        }
        onNodeWithTag("previewResetView").assertIsDisplayed()
        onNodeWithTag("previewWatermarkTarget").assertExists()
    }

    @Test
    fun singleDragIntoBottomTrashArmsAndClears_draggingBackOutDisarmsAndCommitsMove() = runComposeUiTest {
        val photo = bitmap(300, 300, Color.White)
        val cell = bitmap(60, 30, Color.Red)
        val config = WaterMark.default.copy(tileMode = WatermarkTileMode.CLAMP, degree = 0f)
        var offset by mutableStateOf(Offset(.4f, .4f))
        var overlay by mutableStateOf(overlayCellFrom(cell, config, offset.x, offset.y, 300))
        var commits = 0
        var cleared = 0
        setContent {
            AppTheme {
                CompositionLocalProvider(LocalPreviewInteraction provides PreviewInteraction(
                    "photo",
                    config,
                    {},
                    {},
                    onClear = {
                        cleared++
                        overlay = emptyOverlayCellFor(config, 300)
                    },
                )) {
                    LiveOverlayPreview(
                        OverlayPreviewChrome.LiveLayers,
                        photo,
                        overlay,
                        null,
                        modifier = Modifier.size(width = 300.dp, height = 348.dp),
                        onOffsetCommit = { x, y ->
                            commits++
                            offset = Offset(x, y)
                            overlay = overlay.withOffset(x, y)
                        },
                        renderGestureCell = { cell },
                    )
                }
            }
        }
        onNodeWithTag("previewGestureSurface").performTouchInput {
            down(Offset(width * .5f, height * .45f))
            moveTo(Offset(width * .5f, height * .55f))
        }
        val surfaceBounds = onNodeWithTag("previewGestureSurface").fetchSemanticsNode().boundsInRoot
        val trashZone = onNodeWithTag("previewDragTrashZone").assertIsDisplayed()
        // Sticker v3 §1.5: the drag-to-remove target is a 40.dp chip, no longer a 48.dp circle pill.
        trashZone.assertHeightIsEqualTo(40.dp)
        val trashCenter = trashZone.fetchSemanticsNode().boundsInRoot.center
        val trashLocal = trashCenter - surfaceBounds.topLeft
        onNodeWithTag("previewDragTrashArmed").assertDoesNotExist()

        onNodeWithTag("previewGestureSurface").performTouchInput { moveTo(trashLocal) }
        onNodeWithTag("previewDragTrashArmed").assertIsDisplayed()

        // Dragging back out disarms trash and commits move on release.
        onNodeWithTag("previewGestureSurface").performTouchInput {
            moveTo(Offset(width * .75f, height * .45f))
        }
        onNodeWithTag("previewDragTrashZone").assertIsDisplayed()
        onNodeWithTag("previewDragTrashArmed").assertDoesNotExist()
        onNodeWithTag("previewGestureSurface").performTouchInput { up() }
        waitUntil { commits == 1 }
        runOnIdle { assertEquals(0, cleared) }
        onNodeWithTag("previewDragTrashZone").assertDoesNotExist()

        // Dragging into trash and releasing calls onClear without committing offset.
        onNodeWithTag("previewGestureSurface").performTouchInput {
            down(Offset(width * .75f, height * .45f))
            moveTo(trashLocal)
        }
        onNodeWithTag("previewDragTrashArmed").assertIsDisplayed()
        onNodeWithTag("previewGestureSurface").performTouchInput { up() }
        waitUntil { cleared == 1 }
        runOnIdle { assertEquals(1, commits) }
        onNodeWithTag("previewDragTrashZone").assertDoesNotExist()
        onNodeWithTag("previewViewTarget").assertExists()
    }

    @Test
    fun combinedBottomRightHandle_changesSizeAndDegreeInOneTransformAndShowsHud() = runComposeUiTest {
        val photo = bitmap(300, 300, Color.White)
        val cell = bitmap(80, 40, Color.Red)
        var mark by mutableStateOf(WaterMark.default.copy(tileMode = WatermarkTileMode.CLAMP, textSize = 14f, degree = 0f))
        var offset by mutableStateOf(Offset(.35f, .4f))
        val transforms = mutableListOf<WatermarkConfigChange.Transform>()
        setContent {
            AppTheme {
                CompositionLocalProvider(LocalPreviewInteraction provides PreviewInteraction(
                    "photo",
                    mark,
                    { change ->
                        val t = change as WatermarkConfigChange.Transform
                        transforms += t
                        mark = mark.copy(textSize = t.size, degree = t.degree)
                    },
                    {},
                )) {
                    LiveOverlayPreview(
                        OverlayPreviewChrome.LiveLayers,
                        photo,
                        overlayCellFrom(cell, mark, offset.x, offset.y, 300),
                        null,
                        modifier = Modifier.size(width = 300.dp, height = 348.dp),
                        onOffsetCommit = { x, y -> offset = Offset(x, y) },
                        renderGestureCell = { cell },
                    )
                }
            }
        }
        onNodeWithTag("previewGestureSurface").performTouchInput { click(Offset(width * .48f, height * .46f)) }
        onNodeWithTag("previewSelectionAngle").assertDoesNotExist()
        onNodeWithTag("previewSelectionRotate").assertIsDisplayed().performTouchInput {
            down(center)
            moveBy(Offset(24f, 48f))
        }
        onNodeWithTag("previewSelectionAngle").assertIsDisplayed()
            .assertTextContains("%", substring = true)
            .assertTextContains("°", substring = true)
        onNodeWithTag("previewSelectionRotate").performTouchInput { up() }
        waitUntil { transforms.size == 1 }
        runOnIdle {
            assertTrue(transforms.single().size > 14f)
            assertTrue(transforms.single().degree in 10f..25f)
        }
        onNodeWithTag("previewSelectionAngle").assertDoesNotExist()
    }

    @Test
    fun centerMagneticSnap_snapsCenterInLivePreview() = runComposeUiTest {
        val photo = bitmap(300, 300, Color.White)
        val cell = bitmap(60, 60, Color.Red)
        val config = WaterMark.default.copy(tileMode = WatermarkTileMode.CLAMP, degree = 0f)
        var offset by mutableStateOf(Offset(.1f, .1f))
        var overlay by mutableStateOf(overlayCellFrom(cell, config, offset.x, offset.y, 300))
        var commits = 0
        setContent {
            AppTheme {
                CompositionLocalProvider(LocalPreviewInteraction provides PreviewInteraction("photo", config, {}, {})) {
                    LiveOverlayPreview(
                        OverlayPreviewChrome.LiveLayers,
                        photo,
                        overlay,
                        null,
                        modifier = Modifier.size(width = 300.dp, height = 348.dp),
                        onOffsetCommit = { x, y ->
                            commits++
                            offset = Offset(x, y)
                            overlay = overlay.withOffset(x, y)
                        },
                        renderGestureCell = { cell },
                    )
                }
            }
        }
        onNodeWithTag("previewGestureSurface").performTouchInput {
            down(Offset(width * .2f, height * .2f))
            moveTo(Offset(width * .5f + 2f, height * .5f - 2f))
            up()
        }
        waitUntil { commits == 1 }
        runOnIdle {
            assertEquals(0.4f, offset.x, 0.002f)
            assertEquals(0.4f, offset.y, 0.002f)
        }
    }

    @Test
    fun angleMagneticSnap_snapsCardinalDegreeOnHandle() = runComposeUiTest {
        val photo = bitmap(300, 300, Color.White)
        val cell = bitmap(60, 60, Color.Red)
        var mark by mutableStateOf(WaterMark.default.copy(tileMode = WatermarkTileMode.CLAMP, textSize = 14f, degree = 25f))
        var offset by mutableStateOf(Offset(.4f, .4f))
        val transforms = mutableListOf<WatermarkConfigChange.Transform>()
        setContent {
            AppTheme {
                CompositionLocalProvider(LocalPreviewInteraction provides PreviewInteraction(
                    "photo",
                    mark,
                    { change ->
                        val t = change as WatermarkConfigChange.Transform
                        transforms += t
                        mark = mark.copy(textSize = t.size, degree = t.degree)
                    },
                    {},
                )) {
                    LiveOverlayPreview(
                        OverlayPreviewChrome.LiveLayers,
                        photo,
                        overlayCellFrom(cell, mark, offset.x, offset.y, 300),
                        null,
                        modifier = Modifier.size(width = 300.dp, height = 348.dp),
                        onOffsetCommit = { x, y -> offset = Offset(x, y) },
                        renderGestureCell = { cell },
                    )
                }
            }
        }
        onNodeWithTag("previewGestureSurface").performTouchInput { click(Offset(width * .5f, height * .5f)) }
        onNodeWithTag("previewSelectionRotate").assertIsDisplayed().performTouchInput {
            down(center)
            moveBy(Offset(19.6f, -12.5f))
            up()
        }
        waitUntil { transforms.size == 1 }
        runOnIdle { assertEquals(0f, transforms.single().degree, 0.01f) }
    }

    @Test
    fun switchingIconUri_clearsSelectionAndHandles() = runComposeUiTest {
        val photo = bitmap(300, 300, Color.White)
        val cell = bitmap(60, 60, Color.Red)
        var config by mutableStateOf(
            WaterMark.default.copy(
                markMode = WatermarkMode.Image,
                tileMode = WatermarkTileMode.CLAMP,
                iconUri = MediaRef("/tmp/a.png"),
            ),
        )
        setContent {
            AppTheme {
                CompositionLocalProvider(LocalPreviewInteraction provides PreviewInteraction("photo", config, {}, {})) {
                    LiveOverlayPreview(
                        OverlayPreviewChrome.LiveLayers,
                        photo,
                        overlayCellFrom(cell, config, .4f, .4f, 300),
                        null,
                        modifier = Modifier.size(width = 300.dp, height = 348.dp),
                        onOffsetCommit = { _, _ -> },
                        renderGestureCell = { cell },
                    )
                }
            }
        }
        onNodeWithTag("previewGestureSurface").performTouchInput { click(Offset(width * .5f, height * .5f)) }
        onNodeWithTag("previewWatermarkTarget").assertExists()
        onNodeWithTag("previewSelectionRotate").assertIsDisplayed()

        runOnIdle { config = config.copy(iconUri = MediaRef("/tmp/b.png")) }
        onNodeWithTag("previewViewTarget").assertExists()
        onNodeWithTag("previewSelectionRotate").assertDoesNotExist()
    }

    @Test
    fun cancelledHandleDrag_commitsNothing() = runComposeUiTest {
        val photo = bitmap(300, 300, Color.White)
        val cell = bitmap(80, 40, Color.Red)
        var mark by mutableStateOf(WaterMark.default.copy(tileMode = WatermarkTileMode.CLAMP, textSize = 14f, degree = 0f))
        val transforms = mutableListOf<WatermarkConfigChange.Transform>()
        var commits = 0
        var interceptCancel by mutableStateOf(false)
        setContent {
            AppTheme {
                CompositionLocalProvider(LocalPreviewInteraction provides PreviewInteraction(
                    "photo",
                    mark,
                    { change ->
                        val t = change as WatermarkConfigChange.Transform
                        transforms += t
                        mark = mark.copy(textSize = t.size, degree = t.degree)
                    },
                    {},
                )) {
                    Box(
                        Modifier.pointerInput(Unit) {
                            awaitPointerEventScope {
                                while (true) {
                                    val event = awaitPointerEvent(PointerEventPass.Initial)
                                    if (interceptCancel) {
                                        event.changes.forEach { it.consume() }
                                    }
                                }
                            }
                        },
                    ) {
                        LiveOverlayPreview(
                            OverlayPreviewChrome.LiveLayers,
                            photo,
                            overlayCellFrom(cell, mark, .35f, .4f, 300),
                            null,
                            modifier = Modifier.size(width = 300.dp, height = 348.dp),
                            onOffsetCommit = { _, _ -> commits++ },
                            renderGestureCell = { cell },
                        )
                    }
                }
            }
        }
        onNodeWithTag("previewGestureSurface").performTouchInput { click(Offset(width * .48f, height * .46f)) }
        onNodeWithTag("previewSelectionRotate").assertIsDisplayed().performTouchInput {
            down(center)
            moveBy(Offset(20f, 36f))
        }
        onNodeWithTag("previewSelectionAngle").assertIsDisplayed()
        runOnIdle { interceptCancel = true }
        onNodeWithTag("previewSelectionRotate").performTouchInput {
            moveBy(Offset(4f, 4f))
            up()
        }
        waitForIdle()
        runOnIdle {
            assertTrue(transforms.isEmpty())
            assertEquals(0, commits)
            assertEquals(0f, mark.degree)
            assertEquals(14f, mark.textSize)
        }
        onNodeWithTag("previewSelectionAngle").assertDoesNotExist()
    }

    @Test
    fun concurrentHandleAndBodyDrag_isolatesOwnership() = runComposeUiTest {
        val photo = bitmap(300, 300, Color.White)
        val cell = bitmap(80, 40, Color.Red)
        var mark by mutableStateOf(WaterMark.default.copy(tileMode = WatermarkTileMode.CLAMP, textSize = 14f, degree = 0f))
        var offset by mutableStateOf(Offset(.35f, .4f))
        var overlay by mutableStateOf(overlayCellFrom(cell, mark, offset.x, offset.y, 300))
        val transforms = mutableListOf<WatermarkConfigChange.Transform>()
        var commits = 0
        setContent {
            AppTheme {
                CompositionLocalProvider(LocalPreviewInteraction provides PreviewInteraction(
                    "photo",
                    mark,
                    { change ->
                        val t = change as WatermarkConfigChange.Transform
                        transforms += t
                        mark = mark.copy(textSize = t.size, degree = t.degree)
                        overlay = overlayCellFrom(cell, mark, offset.x, offset.y, 300)
                    },
                    {},
                )) {
                    LiveOverlayPreview(
                        OverlayPreviewChrome.LiveLayers,
                        photo,
                        overlay,
                        null,
                        modifier = Modifier.size(width = 300.dp, height = 348.dp),
                        onOffsetCommit = { x, y ->
                            commits++
                            offset = Offset(x, y)
                            overlay = overlay.withOffset(x, y)
                        },
                        renderGestureCell = { cell },
                    )
                }
            }
        }
        onNodeWithTag("previewGestureSurface").performTouchInput { click(Offset(width * .48f, height * .46f)) }

        // Part A: handle transform active -> body drag on previewGestureSurface must be ignored.
        onNodeWithTag("previewSelectionRotate").performTouchInput {
            down(0, center)
            moveBy(0, Offset(0f, 36f))
        }
        onNodeWithTag("previewGestureSurface").performTouchInput {
            down(1, Offset(width * .48f, height * .46f))
            moveTo(1, Offset(width * .7f, height * .7f))
            up(1)
        }
        runOnIdle { assertEquals(0, commits) }
        onNodeWithTag("previewSelectionRotate").performTouchInput { up(0) }
        waitUntil { transforms.size == 1 }
        runOnIdle { assertTrue(offset.x < 0.45f) }

        // Part B: body drag active -> handle drag must be ignored.
        transforms.clear()
        val beforeCommits = commits
        onNodeWithTag("previewGestureSurface").performTouchInput {
            down(0, Offset(width * .48f, height * .46f))
            moveTo(0, Offset(width * .65f, height * .65f))
        }
        onNodeWithTag("previewSelectionRotate").performTouchInput {
            down(1, center)
            moveBy(1, Offset(0f, 40f))
            up(1)
        }
        onNodeWithTag("previewGestureSurface").performTouchInput { up(0) }
        waitUntil { commits == beforeCommits + 1 }
        runOnIdle {
            assertTrue(transforms.isEmpty(), "handle must not transform while body drag is locked")
            assertTrue(offset.x > 0.5f)
        }
    }

    @Test
    fun clampSingleWatermarkCenter_elongated45DegreeIsIdempotentWithZeroDrift() {
        val photoSize = IntSize(300, 300)
        val cLeft1 = clampSingleWatermarkCenter(Offset(-50f, 150f), photoSize, 140f, 36f, 45f)
        val cLeft2 = clampSingleWatermarkCenter(Offset(-120f, 150f), photoSize, 140f, 36f, 45f)
        assertEquals(150f, cLeft1.y, 0.01f)
        assertEquals(150f, cLeft2.y, 0.01f)
        assertEquals(cLeft1.x, cLeft2.x, 1e-3f)
        assertEquals(cLeft1.y, cLeft2.y, 1e-3f)

        val cLeftIdem = clampSingleWatermarkCenter(cLeft1, photoSize, 140f, 36f, 45f)
        assertEquals(cLeft1.x, cLeftIdem.x, 1e-3f)
        assertEquals(cLeft1.y, cLeftIdem.y, 1e-3f)

        val cCorner = clampSingleWatermarkCenter(Offset(-200f, -200f), photoSize, 140f, 36f, 45f)
        val cCornerIdem = clampSingleWatermarkCenter(cCorner, photoSize, 140f, 36f, 45f)
        assertEquals(cCorner.x, cCornerIdem.x, 1e-3f)
        assertEquals(cCorner.y, cCornerIdem.y, 1e-3f)
    }

    @Test
    fun stationaryTwoFingerTap_on358DegreeNearCenterMark_commitsNothing() = runComposeUiTest {
        val photo = bitmap(300, 300, Color.White)
        val cell = bitmap(60, 60, Color.Red)
        val config = WaterMark.default.copy(tileMode = WatermarkTileMode.CLAMP, degree = 358f)
        val changes = mutableListOf<WatermarkConfigChange>()
        var commits = 0
        setContent {
            AppTheme {
                CompositionLocalProvider(LocalPreviewInteraction provides PreviewInteraction("photo", config, {
                    changes += it
                }, {})) {
                    LiveOverlayPreview(
                        OverlayPreviewChrome.LiveLayers,
                        photo,
                        overlayCellFrom(cell, config, 122f / 300f, 120f / 300f, 300),
                        null,
                        modifier = Modifier.size(width = 300.dp, height = 348.dp),
                        onOffsetCommit = { _, _ -> commits++ },
                        renderGestureCell = { cell },
                    )
                }
            }
        }
        onNodeWithTag("previewGestureSurface").performTouchInput {
            down(0, Offset(width * .5f, height * .5f))
            down(1, Offset(width * .5f + 16f, height * .5f))
            up(0); up(1)
        }
        waitForIdle()
        runOnIdle {
            assertTrue(changes.isEmpty())
            assertEquals(0, commits)
        }
        onNodeWithTag("previewViewTarget").assertExists()
    }

    @Test
    fun clearedBlankText_behavesAsEmptyOverlayAndCannotBeReselected() = runComposeUiTest {
        val photo = bitmap(300, 300, Color.White)
        val cell = bitmap(60, 30, Color.Red)
        var config by mutableStateOf(WaterMark.default.copy(tileMode = WatermarkTileMode.CLAMP, text = "Hello"))
        var opened = 0
        setContent {
            AppTheme {
                CompositionLocalProvider(LocalPreviewInteraction provides PreviewInteraction("photo", config, {}, { opened++ })) {
                    LiveOverlayPreview(
                        OverlayPreviewChrome.LiveLayers,
                        photo,
                        overlayCellFrom(cell, config, .4f, .4f, 300),
                        null,
                        modifier = Modifier.size(width = 300.dp, height = 348.dp),
                        onOffsetCommit = { _, _ -> },
                        renderGestureCell = { cell },
                    )
                }
            }
        }
        onNodeWithTag("previewGestureSurface").performTouchInput { click(Offset(width * .5f, height * .45f)) }
        onNodeWithTag("previewWatermarkTarget").assertExists()
        onNodeWithTag("previewSelectionRotate").assertIsDisplayed()

        runOnIdle { config = config.copy(text = "") }
        onNodeWithTag("previewViewTarget").assertExists()
        onNodeWithTag("previewSelectionRotate").assertDoesNotExist()

        onNodeWithTag("previewGestureSurface").performTouchInput { click(Offset(width * .5f, height * .45f)) }
        onNodeWithTag("previewViewTarget").assertExists()
        onNodeWithTag("previewWatermarkTarget").assertDoesNotExist()
        runOnIdle { assertEquals(0, opened) }
    }

    @Test
    fun rtlLayout_keepsHandleAlignedWithRotatedBottomRightCorner() = runComposeUiTest {
        val photo = bitmap(300, 300, Color.White)
        val cell = bitmap(80, 40, Color.Red)
        var mark by mutableStateOf(WaterMark.default.copy(tileMode = WatermarkTileMode.CLAMP, textSize = 14f, degree = 0f))
        var offset by mutableStateOf(Offset(.35f, .4f))
        val transforms = mutableListOf<WatermarkConfigChange.Transform>()
        setContent {
            AppTheme {
                CompositionLocalProvider(
                    LocalLayoutDirection provides LayoutDirection.Rtl,
                    LocalPreviewInteraction provides PreviewInteraction(
                        "photo",
                        mark,
                        { change ->
                            val t = change as WatermarkConfigChange.Transform
                            transforms += t
                            mark = mark.copy(textSize = t.size, degree = t.degree)
                        },
                        {},
                    ),
                ) {
                    LiveOverlayPreview(
                        OverlayPreviewChrome.LiveLayers,
                        photo,
                        overlayCellFrom(cell, mark, offset.x, offset.y, 300),
                        null,
                        modifier = Modifier.size(width = 300.dp, height = 348.dp),
                        onOffsetCommit = { x, y -> offset = Offset(x, y) },
                        renderGestureCell = { cell },
                    )
                }
            }
        }
        onNodeWithTag("previewGestureSurface").performTouchInput { click(Offset(width * .48f, height * .46f)) }
        val surfaceBounds = onNodeWithTag("previewGestureSurface").fetchSemanticsNode().boundsInRoot
        val handleBounds = onNodeWithTag("previewSelectionRotate").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        val relCenter = handleBounds.center - surfaceBounds.topLeft
        assertTrue(abs(relCenter.x - 193f) < 3f, "RTL handle X must align physically near 193 (got ${relCenter.x})")
        assertTrue(abs(relCenter.y - 168f) < 3f, "RTL handle Y must align physically near 168 (got ${relCenter.y})")

        onNodeWithTag("previewSelectionRotate").performTouchInput {
            down(center)
            moveBy(Offset(20f, 36f))
            up()
        }
        waitUntil { transforms.size == 1 }
    }
}
