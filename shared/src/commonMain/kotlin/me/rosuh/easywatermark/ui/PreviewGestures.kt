package me.rosuh.easywatermark.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.*
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.center
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.input.pointer.*
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.filterNotNull
import me.rosuh.easywatermark.data.model.*
import me.rosuh.easywatermark.shared.generated.resources.*
import org.jetbrains.compose.resources.stringResource
import kotlin.math.*

internal data class PreviewInteraction(
    val identity: String,
    val config: WaterMark,
    val onConfig: (WatermarkConfigChange) -> Unit,
    val onEditText: () -> Unit,
)
internal val LocalPreviewInteraction = staticCompositionLocalOf<PreviewInteraction?> { null }

internal data class PreviewCellHit(val origin: Offset, val column: Int, val row: Int)

/** All coordinates here are photo pixels, after undoing the view transform and Fit. */
internal fun previewCellHit(
    point: Offset, photo: Size, cell: Size, origin: Offset, repeat: Boolean,
): PreviewCellHit? {
    if (photo.width <= 0 || photo.height <= 0 || cell.width <= 0 || cell.height <= 0) return null
    if (!point.x.isFinite() || !point.y.isFinite() || point.x !in 0f..photo.width || point.y !in 0f..photo.height) return null
    val col = if (repeat) floor((point.x - origin.x) / cell.width).toInt() else 0
    val row = if (repeat) floor((point.y - origin.y) / cell.height).toInt() else 0
    val topLeft = origin + Offset(col * cell.width, row * cell.height)
    return if (point.x >= topLeft.x && point.x <= topLeft.x + cell.width &&
        point.y >= topLeft.y && point.y <= topLeft.y + cell.height
    ) PreviewCellHit(topLeft, col, row) else null
}

internal fun photoPoint(point: Offset, box: Size, fitted: FittedImageRect, zoom: Float, pan: Offset): Offset {
    val unzoomed = (point - box.center - pan) / zoom + box.center
    return Offset((unzoomed.x - fitted.left) / fitted.width, (unzoomed.y - fitted.top) / fitted.height)
}

internal fun boundedViewPan(pan: Offset, zoom: Float, fitted: FittedImageRect): Offset = Offset(
    pan.x.coerceIn(-fitted.width * (zoom - 1) / 2, fitted.width * (zoom - 1) / 2),
    pan.y.coerceIn(-fitted.height * (zoom - 1) / 2, fitted.height * (zoom - 1) / 2),
)

private data class PreviewDraft(
    val baseConfig: WaterMark,
    val config: WaterMark,
    val center: Offset,
    val hit: PreviewCellHit,
    val repeat: Boolean,
    // 0: finger draft, 1: final raster requested, 2: submitted to the existing host.
    val stage: Int = 0,
) {
    fun offset(cell: ImageBitmap, photo: ImageBitmap): Offset {
        val x = (center.x - (hit.column + .5f) * cell.width) / photo.width
        val y = (center.y - (hit.row + .5f) * cell.height) / photo.height
        return if (repeat) Offset(x, y) else Offset(
            x.coerceIn(0f, (1f - cell.width.toFloat() / photo.width).coerceAtLeast(0f)),
            y.coerceIn(0f, (1f - cell.height.toFloat() / photo.height).coerceAtLeast(0f)),
        )
    }
}

/** UI-only view/selection/draft state; hosts retain Session and source ownership. */
@Composable
internal fun InteractiveOverlayPreview(
    photo: ImageBitmap,
    overlay: OverlayCell,
    interaction: PreviewInteraction,
    onOffsetCommit: (Float, Float) -> Unit,
    renderCell: suspend (WaterMark) -> ImageBitmap,
    contentDescription: String?,
) {
    key(interaction.identity) {
        var box by remember { mutableStateOf(IntSize.Zero) }
        var zoom by remember { mutableFloatStateOf(1f) }
        var pan by remember { mutableStateOf(Offset.Zero) }
        var selected by remember { mutableStateOf<PreviewCellHit?>(null) }
        var draft by remember { mutableStateOf<PreviewDraft?>(null) }
        var raster by remember { mutableStateOf<Pair<WaterMark, ImageBitmap>?>(null) }
        var dragging by remember { mutableStateOf(false) }
        var renderFailed by remember { mutableStateOf(false) }
        val current by rememberUpdatedState(interaction)
        val currentOverlay by rememberUpdatedState(overlay)
        val currentCommit by rememberUpdatedState(onOffsetCommit)
        val currentRender by rememberUpdatedState(renderCell)
        // One conflated cell-only raster at a time, including the exact final cell before commit.
        LaunchedEffect(Unit) {
            snapshotFlow { draft }.filterNotNull().conflate().collect { request ->
                if (request.stage == 2) return@collect
                val cell = try {
                    if (raster?.first == request.config) raster!!.second else currentRender(request.config)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    draft = null; raster = null; renderFailed = true
                    return@collect
                }
                if (draft == null) return@collect
                if (request.stage == 1 && current.config != request.baseConfig) {
                    if (draft == request) { draft = null; raster = null }
                    return@collect
                }
                raster = request.config to cell
                if (request.stage == 1 && draft == request && current.config == request.baseConfig) {
                    val offset = request.offset(cell, photo)
                    val config = request.config.copy(
                        repeatOffsetX = if (request.repeat) offset.x else request.config.repeatOffsetX,
                        repeatOffsetY = if (request.repeat) offset.y else request.config.repeatOffsetY,
                    )
                    draft = request.copy(config = config, stage = 2)
                    raster = config to cell
                    if (config != request.baseConfig) current.onConfig(WatermarkConfigChange.Transform(
                        config.textSize, config.degree,
                        if (request.repeat) offset.x else null, if (request.repeat) offset.y else null,
                    ))
                    if (!request.repeat) currentCommit(offset.x, offset.y)
                }
            }
        }
        LaunchedEffect(overlay, interaction.config, dragging) {
            val pending = draft
            if (!dragging && pending?.stage == 2 && (
                (overlay.renderedConfig == pending.config &&
                    abs(overlay.offsetX - pending.offset(overlay.cell, photo).x) < .0001f &&
                    abs(overlay.offsetY - pending.offset(overlay.cell, photo).y) < .0001f) ||
                (current.config != pending.baseConfig && current.config != pending.config)
            )) { draft = null; raster = null }
        }
        val shownCell = raster?.takeIf { draft != null }?.second ?: overlay.cell
        val shownOffset = draft?.offset(shownCell, photo) ?: Offset(overlay.offsetX, overlay.offsetY)
        val shownOverlay = overlay.copy(cell = shownCell, offsetX = shownOffset.x, offsetY = shownOffset.y)
        val fitted = computeFittedImageRect(box.width.toFloat(), box.height.toFloat(), photo.width.toFloat(), photo.height.toFloat())
        val selectionColor = MaterialTheme.colorScheme.primary
        Box(Modifier.fillMaxSize().clipToBounds().onSizeChanged { box = it }) {
            Box(
                Modifier.fillMaxSize().graphicsLayer {
                    scaleX = zoom; scaleY = zoom; translationX = pan.x; translationY = pan.y
                },
            ) {
                Image(photo, contentDescription, Modifier.fillMaxSize(), contentScale = ContentScale.Fit, filterQuality = FilterQuality.High)
                Canvas(Modifier.fillMaxSize()) {
                    drawLiveOverlayLayer(photo.width, photo.height, shownOverlay)
                    val hit = selected
                    val fit = fitted
                    if (hit != null && fit != null) {
                        val sx = fit.width / photo.width
                        val sy = fit.height / photo.height
                        val origin = Offset(shownOffset.x * photo.width, shownOffset.y * photo.height) +
                            Offset(hit.column * shownCell.width.toFloat(), hit.row * shownCell.height.toFloat())
                        val topLeft = Offset(fit.left + origin.x * sx, fit.top + origin.y * sy)
                        drawRect(selectionColor, topLeft, Size(shownCell.width * sx, shownCell.height * sy), style = Stroke(2f / zoom))
                    }
                }
            }
            Box(Modifier.fillMaxSize().testTag(if (selected == null) "previewViewTarget" else "previewWatermarkTarget")
                .pointerInput(Unit) {
                    awaitPointerEventScope {
                        while (true) {
                            val event = awaitPointerEvent()
                            if (event.type != PointerEventType.Scroll || dragging) continue
                            val change = event.changes.firstOrNull() ?: continue
                            val fit = computeFittedImageRect(size.width.toFloat(), size.height.toFloat(), photo.width.toFloat(), photo.height.toFloat()) ?: continue
                            val center = Offset(size.width / 2f, size.height / 2f)
                            val next = (zoom * exp(-change.scrollDelta.y * .08f)).coerceIn(1f, 4f)
                            pan = boundedViewPan((pan + center - change.position) * (next / zoom) - center + change.position, next, fit)
                            zoom = next
                            event.changes.forEach { it.consume() }
                        }
                    }
                }
                .pointerInput(interaction.identity, photo.width, photo.height) {
                    var previousTap: Pair<Long, Offset>? = null
                    awaitEachGesture {
                        val firstDown = awaitFirstDown(requireUnconsumed = false)
                        if ((draft?.stage ?: 0) > 0) { firstDown.consume(); return@awaitEachGesture }
                        val fit = computeFittedImageRect(size.width.toFloat(), size.height.toFloat(), photo.width.toFloat(), photo.height.toFloat()) ?: return@awaitEachGesture
                        val boxSize = Size(size.width.toFloat(), size.height.toFloat())
                        val config = current.config
                        val startOverlay = currentOverlay
                        val repeat = startOverlay.tileMode == WatermarkTileMode.REPEAT
                        val origin = Offset(startOverlay.offsetX * photo.width, startOverlay.offsetY * photo.height)
                        val cellSize = Size(startOverlay.cell.width.toFloat(), startOverlay.cell.height.toFloat())
                        val pixels = startOverlay.cell.toPixelMap()
                        fun hitAt(position: Offset): PreviewCellHit? {
                            val normalized = photoPoint(position, boxSize, fit, zoom, pan)
                            val point = Offset(normalized.x * photo.width, normalized.y * photo.height)
                            val candidate = previewCellHit(point, Size(photo.width.toFloat(), photo.height.toFloat()), cellSize, origin, repeat)
                            // Transparent gap is photo space; keep a bounded margin for small text.
                            return candidate?.takeIf { startOverlay.alpha > .01f }?.takeIf {
                                val local = point - it.origin
                                val radius = (12.dp.toPx() * photo.width / fit.width / zoom).coerceAtMost(24f)
                                (-2..2).any { dx -> (-2..2).any { dy ->
                                    val x = (local.x + dx * radius / 2).toInt()
                                    val y = (local.y + dy * radius / 2).toInt()
                                    x in 0 until pixels.width && y in 0 until pixels.height && pixels[x, y].alpha > .05f
                                } }
                            }
                        }
                        // Simultaneous native touches have no temporal first pointer. Prefer a
                        // watermark hit in that initial event; later fingers never retarget.
                        val down = currentEvent.changes.firstOrNull {
                            it.pressed && !it.previousPressed && hitAt(it.position) != null
                        } ?: firstDown
                        var hit = hitAt(down.position)
                        selected = hit
                        renderFailed = false
                        down.consume()
                        val startId = current.identity
                        var scale = 1f
                        var rotation = 0f
                        var translation = Offset.Zero
                        var moved = false
                        var multi = false
                        var cancelled = false
                        var lastPosition = down.position
                        val initialPointerCount = currentEvent.changes.count { it.pressed }
                        val gestureBench = ClampDragBench.previewScope("preview_gesture")
                        var addedPointerDelayMs: Long? = null
                        dragging = true
                        try {
                            do {
                                val event = awaitPointerEvent()
                                if (event.changes.any { it.isConsumed }) { cancelled = true; break }
                                val added = event.changes.firstOrNull { it.pressed && !it.previousPressed }
                                if (added != null) addedPointerDelayMs = added.uptimeMillis - firstDown.uptimeMillis
                                // UIKit may deliver simultaneous starts in separate pointer events.
                                if (!moved && hit == null && added?.uptimeMillis == firstDown.uptimeMillis) {
                                    hit = hitAt(added.position)
                                    selected = hit
                                }
                                val pressed = event.changes.count { it.pressed }
                                if (pressed > 1) { multi = true; previousTap = null }
                                val deltaPan = event.calculatePan()
                                val deltaZoom = event.calculateZoom()
                                val deltaRotation = event.calculateRotation()
                                if (pressed > 0) {
                                    lastPosition = event.changes.first().position
                                    moved = moved || (lastPosition - down.position).getDistance() > viewConfiguration.touchSlop || multi
                                    if (moved) {
                                        previousTap = null
                                        val target = hit
                                        if (target != null) {
                                            scale = (scale * deltaZoom).coerceIn(WatermarkConfigRules.MIN_TEXT_SIZE / config.textSize, WatermarkConfigRules.MAX_TEXT_SIZE / config.textSize)
                                            rotation += deltaRotation
                                            translation += deltaPan / zoom
                                            val center = target.origin + Offset(cellSize.width / 2, cellSize.height / 2) +
                                                Offset(translation.x * photo.width / fit.width, translation.y * photo.height / fit.height)
                                            val next = config.copy(textSize = config.textSize * scale, degree = ((config.degree + rotation) % 360 + 360) % 360)
                                            draft = PreviewDraft(config, next, center, target, repeat)
                                        } else {
                                            val centroid = event.calculateCentroid(useCurrent = false)
                                            val oldZoom = zoom
                                            zoom = (zoom * deltaZoom).coerceIn(1f, 4f)
                                            pan = boundedViewPan((pan + boxSize.center - centroid) * (zoom / oldZoom) - boxSize.center + centroid + deltaPan, zoom, fit)
                                        }
                                    }
                                }
                                event.changes.forEach { it.consume() }
                            } while (event.changes.any { it.pressed })
                            if (!cancelled && startId == current.identity && config == current.config) {
                                val committed = draft
                                if (hit != null && moved && committed != null) {
                                    draft = committed.copy(stage = 1)
                                } else if (hit != null && !moved && !multi) {
                                    val prior = previousTap
                                    if (prior != null && down.uptimeMillis - prior.first in viewConfiguration.doubleTapMinTimeMillis..viewConfiguration.doubleTapTimeoutMillis &&
                                        (down.position - prior.second).getDistance() <= viewConfiguration.touchSlop * 2 && config.markMode == WatermarkMode.Text
                                    ) { previousTap = null; current.onEditText() }
                                    else previousTap = down.uptimeMillis to down.position
                                } else previousTap = null
                            } else { draft = null; raster = null; previousTap = null }
                        } finally {
                            dragging = false
                            gestureBench.finish(mapOf("target" to if (hit == null) "view" else "watermark",
                                "initialPointers" to initialPointerCount, "addedDelayMs" to addedPointerDelayMs))
                            if (current.identity != startId || cancelled || draft?.stage == 0) { draft = null; raster = null; previousTap = null }
                        }
                    }
                })
            if (renderFailed) Text(
                stringResource(Res.string.dialog_save_error_render),
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.align(Alignment.BottomCenter),
            )
            Row(Modifier.align(Alignment.TopEnd)) {
                if (selected != null && interaction.config.markMode == WatermarkMode.Text) {
                    TextButton(onClick = interaction.onEditText, modifier = Modifier.testTag("previewEditText")) {
                        Text(stringResource(Res.string.dialog_title_edit_watermark))
                    }
                }
                if (zoom != 1f || pan != Offset.Zero) {
                    TextButton(onClick = { zoom = 1f; pan = Offset.Zero }, modifier = Modifier.testTag("previewResetView")) {
                        Text(stringResource(Res.string.preview_reset_view))
                    }
                }
            }
        }
    }
}
