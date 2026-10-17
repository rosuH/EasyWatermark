package me.rosuh.easywatermark.ui

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
import androidx.compose.ui.test.*
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import me.rosuh.easywatermark.data.model.*
import me.rosuh.easywatermark.render.OverlayPreviewChrome
import me.rosuh.easywatermark.ui.theme.AppTheme
import androidx.compose.ui.platform.testTag
import kotlinx.coroutines.CompletableDeferred
import kotlin.test.*

@OptIn(ExperimentalTestApi::class)
class PreviewGestureUiTest {
    private fun bitmap(width: Int, height: Int, color: Color): ImageBitmap {
        val bitmap = ImageBitmap(width, height)
        CanvasDrawScope().draw(Density(1f), LayoutDirection.Ltr, Canvas(bitmap), Size(width.toFloat(), height.toFloat())) { drawRect(color) }
        return bitmap
    }

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
                        modifier = Modifier.size(300.dp).testTag("gesturePane"),
                        onOffsetCommit = { _, _ -> }, renderGestureCell = { cell })
                }
            }
        }
        onNodeWithTag("gesturePane").performTouchInput { doubleClick(Offset(width * .5f, height * .45f)) }
        runOnIdle { assertEquals(1, opened) }
        onNodeWithTag("gesturePane").performTouchInput { doubleClick(Offset(width * .1f, height * .1f)) }
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
                        modifier = Modifier.size(300.dp).testTag("gesturePane"),
                        onOffsetCommit = { x, y -> commits++; offset = Offset(x, y); overlay = overlay.withOffset(x, y) },
                        renderGestureCell = { cell })
                }
            }
        }
        onNodeWithTag("gesturePane").performTouchInput {
            down(Offset(width * .5f, height * .45f))
            moveTo(Offset(width * .6f, height * .55f))
        }
        runOnIdle { assertEquals(0, commits) }
        onNodeWithTag("gesturePane").performTouchInput { up() }
        waitUntil { commits == 1 }
        runOnIdle { assertTrue(offset.x > .4f && offset.y > .4f) }
        // Starting in blank space and crossing the watermark keeps the view target.
        onNodeWithTag("gesturePane").performTouchInput { swipe(Offset(width * .1f, height * .1f), Offset(width * .8f, height * .8f)) }
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
                        modifier = Modifier.size(300.dp).testTag("gesturePane"),
                        onOffsetCommit = { x, y -> offset = Offset(x, y) }, renderGestureCell = { cell })
                }
            }
        }
        onNodeWithTag("gesturePane").performTouchInput {
            down(0, Offset(width * .45f, height * .45f))
            down(1, Offset(width * .8f, height * .8f))
            moveTo(0, Offset(width * .35f, height * .35f))
            moveTo(1, Offset(width * .8f, height * .9f))
            up(0); up(1)
        }
        waitUntil { changes.size == 1 }
        runOnIdle { assertTrue(changes.single().size > 14f); assertNotEquals(315f, changes.single().degree) }
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
                        modifier = Modifier.size(300.dp).testTag("gesturePane"),
                        onOffsetCommit = { _, _ -> error("view changed Session offset") }, renderGestureCell = { cell })
                }
            }
        }
        onNodeWithTag("gesturePane").performTouchInput {
            down(0, Offset(width * .1f, height * .1f))
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
                        modifier = Modifier.size(300.dp).testTag("gesturePane"),
                        onOffsetCommit = { _, _ -> error("superseded offset must not commit") },
                        renderGestureCell = { rendering = true; gate.await(); cell })
                }
            }
        }
        onNodeWithTag("gesturePane").performTouchInput {
            swipe(Offset(width * .5f, height * .45f), Offset(width * .6f, height * .55f))
        }
        waitUntil { rendering }
        runOnIdle { config = config.copy(text = "Changed elsewhere") }
        runOnIdle { gate.complete(Unit) }
        waitForIdle()
        onNodeWithTag("gesturePane").performTouchInput {
            doubleClick(Offset(width * .5f, height * .45f))
        }
        waitUntil { opened == 1 }
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
}
