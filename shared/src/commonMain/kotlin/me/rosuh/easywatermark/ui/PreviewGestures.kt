package me.rosuh.easywatermark.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.AbsoluteAlignment
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.center
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.*
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.filterNotNull
import me.rosuh.easywatermark.data.model.*
import me.rosuh.easywatermark.render.RasteredWatermarkCell
import me.rosuh.easywatermark.render.drawWatermarkTiles
import me.rosuh.easywatermark.shared.generated.resources.*
import org.jetbrains.compose.resources.stringResource
import org.jetbrains.compose.resources.painterResource
import me.rosuh.easywatermark.ui.theme.*
import kotlin.math.*

internal data class PreviewInteraction(
    val identity: String,
    val config: WaterMark,
    val onConfig: (WatermarkConfigChange) -> Unit,
    val onEditText: () -> Unit,
    val onClear: () -> Unit = {},
)
internal val PreviewToolbarHeight = 48.dp
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

internal data class RotatedSelectionFrame(
    val centerCanvas: Offset,
    val halfW: Float,
    val halfH: Float,
    val degree: Float,
    val topLeftCanvas: Offset,
    val topRightCanvas: Offset,
    val bottomLeftCanvas: Offset,
    val bottomRightCanvas: Offset,
)

/** Computes the tight watermark selection rect rotated clockwise by [degree] around [centerCanvas]. */
internal fun computeRotatedSelectionFrame(
    centerCanvas: Offset,
    contentWCanvas: Float,
    contentHCanvas: Float,
    framePadPx: Float,
    minSizePx: Float,
    degree: Float,
): RotatedSelectionFrame {
    val safeW = if (contentWCanvas.isFinite() && contentWCanvas > 0f) contentWCanvas else minSizePx
    val safeH = if (contentHCanvas.isFinite() && contentHCanvas > 0f) contentHCanvas else minSizePx
    val halfW = max(safeW / 2f + framePadPx, minSizePx / 2f)
    val halfH = max(safeH / 2f + framePadPx, minSizePx / 2f)
    val rad = degree.toDouble() * PI / 180.0
    val cosA = cos(rad).toFloat()
    val sinA = sin(rad).toFloat()
    fun rotatePoint(dx: Float, dy: Float): Offset = Offset(
        x = centerCanvas.x + dx * cosA - dy * sinA,
        y = centerCanvas.y + dx * sinA + dy * cosA,
    )
    return RotatedSelectionFrame(
        centerCanvas = centerCanvas,
        halfW = halfW,
        halfH = halfH,
        degree = degree,
        topLeftCanvas = rotatePoint(-halfW, -halfH),
        topRightCanvas = rotatePoint(halfW, -halfH),
        bottomLeftCanvas = rotatePoint(-halfW, halfH),
        bottomRightCanvas = rotatePoint(halfW, halfH),
    )
}

/**
 * Clamps a single (CLAMP) watermark center so any edge or corner of the mark can slide freely
 * past the photo border, leaving a small grabbable sliver inside the photo bounds.
 */
internal fun clampSingleWatermarkCenter(
    center: Offset,
    photoSize: IntSize,
    contentWidth: Float,
    contentHeight: Float,
    degree: Float,
): Offset {
    val photoW = photoSize.width.toFloat()
    val photoH = photoSize.height.toFloat()
    if (photoW <= 0f || photoH <= 0f) return center
    if (!center.x.isFinite() || !center.y.isFinite() || !degree.isFinite()) return center

    val halfW = contentWidth / 2f
    val halfH = contentHeight / 2f
    if (!halfW.isFinite() || !halfH.isFinite() || halfW <= 0f || halfH <= 0f) {
        return Offset(center.x.coerceIn(0f, photoW), center.y.coerceIn(0f, photoH))
    }
    val minHalf = min(halfW, halfH)
    val keepInside = min(minHalf * 0.25f, min(photoW, photoH) * 0.04f)
        .coerceAtLeast(min(12f, minHalf))
    val maxLocalX = (halfW - keepInside).coerceAtLeast(0f)
    val maxLocalY = (halfH - keepInside).coerceAtLeast(0f)

    val rad = degree.toDouble() * PI / 180.0
    val cosA = cos(rad).toFloat()
    val sinA = sin(rad).toFloat()
    val ux = cosA
    val uy = sinA
    val vx = -sinA
    val vy = cosA

    // Minkowski sum Box_XY ∩ Box_UV (Photo ⊕ (-RotatedInnerBox)) ensures exact nearest-point projection
    // with zero drift along rotated edges and idempotence: clamp(clamp(p)) == clamp(p).
    val extentX = maxLocalX * abs(ux) + maxLocalY * abs(vx)
    val extentY = maxLocalX * abs(uy) + maxLocalY * abs(vy)
    val minX = -extentX
    val maxX = photoW + extentX
    val minY = -extentY
    val maxY = photoH + extentY

    val minQu = (if (ux < 0f) photoW * ux else 0f) + (if (uy < 0f) photoH * uy else 0f)
    val maxQu = (if (ux > 0f) photoW * ux else 0f) + (if (uy > 0f) photoH * uy else 0f)
    val minQv = (if (vx < 0f) photoW * vx else 0f) + (if (vy < 0f) photoH * vy else 0f)
    val maxQv = (if (vx > 0f) photoW * vx else 0f) + (if (vy > 0f) photoH * vy else 0f)
    val minU = minQu - maxLocalX
    val maxU = maxQu + maxLocalX
    val minV = minQv - maxLocalY
    val maxV = maxQv + maxLocalY

    val eps = 1e-3f
    fun isValid(p: Offset): Boolean {
        if (!p.x.isFinite() || !p.y.isFinite()) return false
        val u = p.x * ux + p.y * uy
        val v = p.x * vx + p.y * vy
        return p.x in (minX - eps)..(maxX + eps) &&
            p.y in (minY - eps)..(maxY + eps) &&
            u in (minU - eps)..(maxU + eps) &&
            v in (minV - eps)..(maxV + eps)
    }

    if (isValid(center)) return center

    val cu = center.x * ux + center.y * uy
    val cv = center.x * vx + center.y * vy

    var best: Offset? = null
    var bestDistSq = Float.POSITIVE_INFINITY
    fun consider(candidate: Offset) {
        if (!isValid(candidate)) return
        val dx = candidate.x - center.x
        val dy = candidate.y - center.y
        val distSq = dx * dx + dy * dy
        if (distSq < bestDistSq) {
            bestDistSq = distSq
            best = candidate
        }
    }

    // 1) Orthogonal projections onto the 4 XY boundary lines.
    consider(Offset(minX, center.y))
    consider(Offset(maxX, center.y))
    consider(Offset(center.x, minY))
    consider(Offset(center.x, maxY))

    // 2) Orthogonal projections onto the 4 UV boundary lines.
    consider(Offset(minU * ux + cv * vx, minU * uy + cv * vy))
    consider(Offset(maxU * ux + cv * vx, maxU * uy + cv * vy))
    consider(Offset(cu * ux + minV * vx, cu * uy + minV * vy))
    consider(Offset(cu * ux + maxV * vx, cu * uy + maxV * vy))

    // 3) 4 corners of Box_XY.
    consider(Offset(minX, minY))
    consider(Offset(minX, maxY))
    consider(Offset(maxX, minY))
    consider(Offset(maxX, maxY))

    // 4) 4 corners of Box_UV.
    for (uVal in floatArrayOf(minU, maxU)) {
        for (vVal in floatArrayOf(minV, maxV)) {
            consider(Offset(uVal * ux + vVal * vx, uVal * uy + vVal * vy))
        }
    }

    // 5) Intersections of XY boundary lines with UV boundary lines.
    val xs = floatArrayOf(minX, maxX)
    val ys = floatArrayOf(minY, maxY)
    val us = floatArrayOf(minU, maxU)
    val vs = floatArrayOf(minV, maxV)
    for (xVal in xs) {
        if (abs(uy) > 1e-5f) for (uVal in us) consider(Offset(xVal, (uVal - xVal * ux) / uy))
        if (abs(vy) > 1e-5f) for (vVal in vs) consider(Offset(xVal, (vVal - xVal * vx) / vy))
    }
    for (yVal in ys) {
        if (abs(ux) > 1e-5f) for (uVal in us) consider(Offset((uVal - yVal * uy) / ux, yVal))
        if (abs(vx) > 1e-5f) for (vVal in vs) consider(Offset((vVal - yVal * vy) / vx, yVal))
    }

    return best ?: Offset(center.x.coerceIn(0f, photoW), center.y.coerceIn(0f, photoH))
}

internal fun hitSingleWatermark(
    point: Offset,
    photo: Size,
    cellCenter: Offset,
    contentWidth: Float,
    contentHeight: Float,
    degree: Float,
    minHalfSize: Float,
    slop: Float,
): Boolean {
    if (!point.x.isFinite() || !point.y.isFinite() ||
        !cellCenter.x.isFinite() || !cellCenter.y.isFinite() ||
        !degree.isFinite() || !minHalfSize.isFinite() || !slop.isFinite()
    ) return false
    if (photo.width <= 0f || photo.height <= 0f) return false
    if (point.x !in -slop..photo.width + slop || point.y !in -slop..photo.height + slop) return false
    val rad = degree.toDouble() * PI / 180.0
    val cosA = cos(rad).toFloat()
    val sinA = sin(rad).toFloat()
    val dx = point.x - cellCenter.x
    val dy = point.y - cellCenter.y
    val localX = dx * cosA + dy * sinA
    val localY = -dx * sinA + dy * cosA
    val safeHalfW = if (contentWidth.isFinite() && contentWidth > 0f) contentWidth / 2f else 0f
    val safeHalfH = if (contentHeight.isFinite() && contentHeight > 0f) contentHeight / 2f else 0f
    val halfW = max(safeHalfW, minHalfSize)
    val halfH = max(safeHalfH, minHalfSize)
    return abs(localX) <= halfW + slop && abs(localY) <= halfH + slop
}

internal data class PositionSnapResult(
    val center: Offset,
    val snappedX: Boolean,
    val snappedY: Boolean,
)

internal fun snapSingleWatermarkCenter(
    rawCenter: Offset,
    photoSize: IntSize,
    wasSnappedX: Boolean,
    wasSnappedY: Boolean,
    enterThresholdPx: Float,
    exitThresholdPx: Float,
): PositionSnapResult {
    if (photoSize.width <= 0 || photoSize.height <= 0 ||
        !rawCenter.x.isFinite() || !rawCenter.y.isFinite()
    ) {
        return PositionSnapResult(rawCenter, snappedX = false, snappedY = false)
    }
    val targetX = photoSize.width / 2f
    val targetY = photoSize.height / 2f
    val thresholdX = if (wasSnappedX) exitThresholdPx else enterThresholdPx
    val thresholdY = if (wasSnappedY) exitThresholdPx else enterThresholdPx
    val snappedX = abs(rawCenter.x - targetX) <= thresholdX
    val snappedY = abs(rawCenter.y - targetY) <= thresholdY
    return PositionSnapResult(
        center = Offset(
            x = if (snappedX) targetX else rawCenter.x,
            y = if (snappedY) targetY else rawCenter.y,
        ),
        snappedX = snappedX,
        snappedY = snappedY,
    )
}

internal data class AngleSnapResult(
    val degree: Float,
    val snapped: Boolean,
    val targetDegree: Float?,
)

private fun normalizeDegree360(degree: Float): Float {
    if (!degree.isFinite()) return 0f
    val mod = ((degree % 360f) + 360f) % 360f
    return if (mod >= 360f || mod < 0f || mod == -0f) 0f else mod
}

internal fun snapWatermarkDegree(
    rawDegree: Float,
    wasSnapped: Boolean,
    enterThresholdDeg: Float = 3.5f,
    exitThresholdDeg: Float = 6.0f,
): AngleSnapResult {
    val normalized = normalizeDegree360(rawDegree)
    val threshold = if (wasSnapped) exitThresholdDeg else enterThresholdDeg
    val cardinals = floatArrayOf(0f, 90f, 180f, 270f)
    var bestCardinal = cardinals[0]
    var bestDist = Float.POSITIVE_INFINITY
    for (cardinal in cardinals) {
        val diff = abs(normalized - cardinal)
        val circularDist = min(diff, 360f - diff)
        if (circularDist < bestDist) {
            bestDist = circularDist
            bestCardinal = cardinal
        }
    }
    return if (bestDist <= threshold) {
        AngleSnapResult(degree = bestCardinal, snapped = true, targetDegree = bestCardinal)
    } else {
        AngleSnapResult(degree = normalized, snapped = false, targetDegree = null)
    }
}

internal fun isInBottomTrashZone(
    fingerView: Offset,
    boxSize: IntSize,
    zoneCenterYOffsetPx: Float,
    hitRadiusPx: Float,
): Boolean {
    if (boxSize.width <= 0 || boxSize.height <= 0) return false
    if (!fingerView.x.isFinite() || !fingerView.y.isFinite() ||
        !zoneCenterYOffsetPx.isFinite() || !hitRadiusPx.isFinite()
    ) return false
    val center = Offset(boxSize.width / 2f, boxSize.height - zoneCenterYOffsetPx)
    return (fingerView - center).getDistance() <= hitRadiusPx
}

private data class PreviewDraft(
    val baseConfig: WaterMark,
    val config: WaterMark,
    val center: Offset,
    val hit: PreviewCellHit,
    val repeat: Boolean,
    val photoSize: IntSize,
    val committedOffset: Offset? = null,
    // 0: finger draft, 1: final raster requested, 2: submitted to the existing host.
    val stage: Int = 0,
) {
    fun offset(
        cellWidth: Int,
        cellHeight: Int,
        contentWidth: Float,
        contentHeight: Float,
    ): Offset {
        committedOffset?.let { return it }
        if (repeat) {
            return Offset(
                (center.x - (hit.column + .5f) * cellWidth) / photoSize.width,
                (center.y - (hit.row + .5f) * cellHeight) / photoSize.height,
            )
        }
        val clamped = clampSingleWatermarkCenter(
            center = center,
            photoSize = photoSize,
            contentWidth = contentWidth,
            contentHeight = contentHeight,
            degree = config.degree,
        )
        return Offset(
            (clamped.x - (hit.column + .5f) * cellWidth) / photoSize.width,
            (clamped.y - (hit.row + .5f) * cellHeight) / photoSize.height,
        )
    }
}

/** UI-only view/selection/draft state; hosts retain Session and source ownership. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun InteractiveOverlayPreview(
    photo: ImageBitmap,
    overlay: OverlayCell,
    interaction: PreviewInteraction,
    onOffsetCommit: (Float, Float) -> Unit,
    renderCell: suspend (WaterMark) -> RasteredWatermarkCell,
    contentDescription: String?,
) {
    key(interaction.identity) {
        var box by remember { mutableStateOf(IntSize.Zero) }
        var zoom by remember { mutableFloatStateOf(1f) }
        var pan by remember { mutableStateOf(Offset.Zero) }
        val scope = rememberCoroutineScope()
        var resetJob by remember { mutableStateOf<Job?>(null) }
        var resetting by remember { mutableStateOf(false) }
        val motionPolicy = currentMotionPolicy()
        val fadeMs = motionDurationMs(motionPolicy, EwmMotionTokens.optionPanelFadeMs)
        val resetMs = motionDurationMs(motionPolicy, EwmMotionTokens.shellShortMs,
            reducedScale = 0f)
        fun interruptReset() { resetJob?.cancel(); resetJob = null; resetting = false }
        LaunchedEffect(motionPolicy) {
            if (motionPolicy != MotionPolicy.Full && resetting) {
                interruptReset(); zoom = 1f; pan = Offset.Zero
            }
        }
        var selected by remember { mutableStateOf<PreviewCellHit?>(null) }
        var draft by remember { mutableStateOf<PreviewDraft?>(null) }
        var raster by remember { mutableStateOf<Pair<WaterMark, RasteredWatermarkCell>?>(null) }
        var dragging by remember { mutableStateOf(false) }
        var transforming by remember { mutableStateOf(false) }
        var gestureMultiTransform by remember { mutableStateOf(false) }
        var showTrashZone by remember { mutableStateOf(false) }
        var overTrash by remember { mutableStateOf(false) }
        var snapX by remember { mutableStateOf(false) }
        var snapY by remember { mutableStateOf(false) }
        var angleSnapped by remember { mutableStateOf(false) }
        var hudSizeRatio by remember { mutableFloatStateOf(1f) }
        var hudDegree by remember { mutableFloatStateOf(interaction.config.degree) }
        var handleTransformActive by remember { mutableStateOf(false) }
        var transformPivot by remember { mutableStateOf(Offset.Zero) }
        var transformLastAngle by remember { mutableFloatStateOf(0f) }
        var transformStartDist by remember { mutableFloatStateOf(1f) }
        var transformStartSize by remember { mutableFloatStateOf(interaction.config.textSize) }
        var transformRawDegree by remember { mutableFloatStateOf(interaction.config.degree) }
        var transformBaseConfig by remember { mutableStateOf(interaction.config) }
        var transformStartPhotoSize by remember { mutableStateOf(IntSize.Zero) }
        var transformStartCenterPhoto by remember { mutableStateOf(Offset.Zero) }
        var transformStartContentW by remember { mutableFloatStateOf(1f) }
        var transformStartContentH by remember { mutableFloatStateOf(1f) }
        var transformHit by remember { mutableStateOf<PreviewCellHit?>(null) }
        var renderFailed by remember { mutableStateOf(false) }
        val photoSize = IntSize(photo.width, photo.height)
        val currentPhotoSize by rememberUpdatedState(photoSize)
        val current by rememberUpdatedState(interaction)
        val currentOverlay by rememberUpdatedState(overlay)
        val currentCommit by rememberUpdatedState(onOffsetCommit)
        val currentRender by rememberUpdatedState(renderCell)
        val haptic = LocalHapticFeedback.current
        val currentHaptic by rememberUpdatedState(haptic)
        val isTextBlank = interaction.config.markMode == WatermarkMode.Text && interaction.config.text.isBlank()
        val overlayEmpty = overlay.alpha <= 0.01f || isTextBlank
        LaunchedEffect(overlay.tileMode, interaction.config.markMode, interaction.config.iconUri, overlayEmpty) {
            selected = null
            transforming = false
            handleTransformActive = false
            gestureMultiTransform = false
            showTrashZone = false
            overTrash = false
            snapX = false
            snapY = false
            angleSnapped = false
            draft = null
            raster = null
        }
        LaunchedEffect(selected) {
            if (selected == null) {
                transforming = false
                handleTransformActive = false
            }
        }
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
                if (request.photoSize != currentPhotoSize) {
                    if (draft == request) { draft = null; raster = null }
                    return@collect
                }
                if (request.stage == 1 && current.config != request.baseConfig) {
                    if (draft == request) { draft = null; raster = null }
                    return@collect
                }
                raster = request.config to cell
                if (request.stage == 1 && draft == request && current.config == request.baseConfig) {
                    val offset = request.offset(
                        cell.logicalWidth,
                        cell.logicalHeight,
                        cell.contentWidth,
                        cell.contentHeight,
                    )
                    val config = request.config.copy(
                        repeatOffsetX = if (request.repeat) offset.x else request.config.repeatOffsetX,
                        repeatOffsetY = if (request.repeat) offset.y else request.config.repeatOffsetY,
                    )
                    draft = request.copy(config = config, committedOffset = offset, stage = 2)
                    raster = config to cell
                    if (config != request.baseConfig) current.onConfig(WatermarkConfigChange.Transform(
                        config.textSize, config.degree,
                        if (request.repeat) offset.x else null, if (request.repeat) offset.y else null,
                    ))
                    if (!request.repeat) currentCommit(offset.x, offset.y)
                }
            }
        }
        LaunchedEffect(overlay, interaction.config, dragging, transforming, draft?.stage) {
            val pending = draft
            val expectedOffset = pending?.offset(
                overlay.logicalWidth,
                overlay.logicalHeight,
                overlay.contentWidth,
                overlay.contentHeight,
            )
            if (!dragging && !transforming && pending?.stage == 2 && expectedOffset != null && (
                (overlay.renderedConfig == pending.config &&
                    abs(overlay.offsetX - expectedOffset.x) < .0001f &&
                    abs(overlay.offsetY - expectedOffset.y) < .0001f) ||
                (current.config != pending.baseConfig && current.config != pending.config)
            )) { draft = null; raster = null }
        }
        val activeDraft = draft?.takeIf { it.photoSize == photoSize }
        val activeRaster = raster?.takeIf { activeDraft != null }?.second
        val shownCell = activeRaster?.bitmap ?: overlay.cell
        val shownLogicalW = activeRaster?.logicalWidth ?: overlay.logicalWidth
        val shownLogicalH = activeRaster?.logicalHeight ?: overlay.logicalHeight
        val shownDrawW = activeRaster?.drawWidth ?: overlay.drawWidth
        val shownDrawH = activeRaster?.drawHeight ?: overlay.drawHeight
        val shownContentW = activeRaster?.contentWidth ?: overlay.contentWidth
        val shownContentH = activeRaster?.contentHeight ?: overlay.contentHeight
        val shownOffset = activeDraft?.offset(
            shownLogicalW,
            shownLogicalH,
            shownContentW,
            shownContentH,
        ) ?: Offset(overlay.offsetX, overlay.offsetY)
        val shownOverlay = overlay.copy(
            cell = shownCell,
            offsetX = shownOffset.x,
            offsetY = shownOffset.y,
            logicalWidth = shownLogicalW,
            logicalHeight = shownLogicalH,
            drawWidth = shownDrawW,
            drawHeight = shownDrawH,
            contentWidth = shownContentW,
            contentHeight = shownContentH,
        )
        val density = LocalDensity.current
        val fitted = computeFittedImageRect(box.width.toFloat(), box.height.toFloat(), photo.width.toFloat(), photo.height.toFloat())
        val selectionColor = editorAccentColor()
        val chromeColor = editorChromeColor()
        val isRepeatOverlay = shownOverlay.tileMode == WatermarkTileMode.REPEAT
        val isSingleOverlay = !isRepeatOverlay && shownOverlay.alpha > 0.01f && !isTextBlank
        val renderedSize = activeRaster?.let { raster?.first?.textSize }
            ?: shownOverlay.renderedConfig?.textSize
            ?: current.config.textSize
        val renderedDegree = activeRaster?.let { raster?.first?.degree }
            ?: shownOverlay.renderedConfig?.degree
            ?: current.config.degree
        val displaySize = activeDraft?.config?.textSize ?: renderedSize
        val displayDegree = activeDraft?.config?.degree ?: renderedDegree
        val visualScaleDelta = if (isSingleOverlay && (transforming || activeDraft != null) && renderedSize > 0f) {
            (displaySize / renderedSize).coerceIn(0.1f, 10f)
        } else {
            1f
        }
        val visualRotateDelta = if (isSingleOverlay && (transforming || activeDraft != null)) {
            displayDegree - renderedDegree
        } else {
            0f
        }
        val trashVisualScale by animateFloatAsState(
            targetValue = if (overTrash && !isRepeatOverlay) 0.72f else 1f,
            animationSpec = tween(fadeMs),
        )
        val trashVisualAlpha by animateFloatAsState(
            targetValue = if (overTrash && !isRepeatOverlay) 0.45f else 1f,
            animationSpec = tween(fadeMs),
        )
        val originPhoto = Offset(shownOffset.x * photo.width, shownOffset.y * photo.height)
        val activeHitCol = selected?.column ?: activeDraft?.hit?.column ?: 0
        val activeHitRow = selected?.row ?: activeDraft?.hit?.row ?: 0
        val liveCenterPhoto = Offset(
            x = originPhoto.x + (activeHitCol + 0.5f) * shownLogicalW,
            y = originPhoto.y + (activeHitRow + 0.5f) * shownLogicalH,
        )
        val liveCenterCanvas = fitted?.let { fit ->
            val sx = fit.width / photo.width
            val sy = fit.height / photo.height
            Offset(fit.left + liveCenterPhoto.x * sx, fit.top + liveCenterPhoto.y * sy)
        }
        val liveSingleFrame: RotatedSelectionFrame? = if (isSingleOverlay && fitted != null && liveCenterCanvas != null) {
            val sx = fitted.width / photo.width
            val sy = fitted.height / photo.height
            val framePadPx = with(density) { 8.dp.toPx() / zoom }
            val minSizePx = with(density) { 32.dp.toPx() / zoom }
            computeRotatedSelectionFrame(
                centerCanvas = liveCenterCanvas,
                contentWCanvas = shownContentW * sx * visualScaleDelta,
                contentHCanvas = shownContentH * sy * visualScaleDelta,
                framePadPx = framePadPx,
                minSizePx = minSizePx,
                degree = displayDegree,
            )
        } else {
            null
        }
        val singleFrame: RotatedSelectionFrame? = liveSingleFrame.takeIf { selected != null }
        val hudFrame: RotatedSelectionFrame? = liveSingleFrame.takeIf { transforming || (gestureMultiTransform && !isRepeatOverlay) }
        Column(Modifier.fillMaxSize()) {
            // Keep controls outside the photo and reserve their height across selection changes.
            Row(Modifier.fillMaxWidth().height(PreviewToolbarHeight).background(MaterialTheme.colorScheme.surface),
                horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(48.dp)) {
                    androidx.compose.animation.AnimatedVisibility(selected != null, enter = fadeIn(tween(fadeMs)), exit = fadeOut(tween(fadeMs))) {
                        val label = stringResource(Res.string.preview_selection_done)
                        TooltipBox(positionProvider = TooltipDefaults.rememberPlainTooltipPositionProvider(),
                            tooltip = { PlainTooltip { Text(stringResource(Res.string.preview_selection_done_hint)) } }, state = rememberTooltipState()) {
                            IconButton(onClick = { selected = null }, enabled = selected != null && !dragging && !transforming && draft == null,
                                modifier = Modifier.size(48.dp).testTag("previewSelectionDone")) {
                                Icon(painterResource(Res.drawable.ic_preview_done), label, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.primary)
                            }
                        }
                    }
                }
                Box(Modifier.size(48.dp)) {
                    androidx.compose.animation.AnimatedVisibility(zoom != 1f || pan != Offset.Zero || resetting,
                        enter = fadeIn(tween(fadeMs)), exit = fadeOut(tween(fadeMs))) {
                        val label = stringResource(Res.string.preview_reset_view)
                        TooltipBox(positionProvider = TooltipDefaults.rememberPlainTooltipPositionProvider(),
                            tooltip = { PlainTooltip { Text(stringResource(Res.string.preview_reset_view_hint)) } }, state = rememberTooltipState()) {
                            IconButton(onClick = {
                                val fromZoom = zoom
                                val fromPan = pan
                                resetting = true
                                resetJob = scope.launch {
                                    try {
                                        animate(0f, 1f, animationSpec = tween(resetMs, easing = FastOutSlowInEasing)) { value, _ ->
                                            zoom = fromZoom + (1f - fromZoom) * value
                                            pan = fromPan * (1f - value)
                                        }
                                        zoom = 1f; pan = Offset.Zero
                                    } finally { resetting = false }
                                }
                            }, enabled = !dragging && !transforming && !resetting,
                                modifier = Modifier.size(48.dp).testTag("previewResetView")) {
                                Icon(painterResource(Res.drawable.ic_preview_fit), label, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurface)
                            }
                        }
                    }
                }
            }
            Box(Modifier.fillMaxWidth().weight(1f).clipToBounds().testTag("previewGestureSurface").onSizeChanged { box = it }) {
                Box(
                    Modifier.fillMaxSize().graphicsLayer {
                        scaleX = zoom; scaleY = zoom; translationX = pan.x; translationY = pan.y
                    },
                ) {
                    Image(photo, contentDescription, Modifier.fillMaxSize(), contentScale = ContentScale.Fit, filterQuality = FilterQuality.High)
                    Canvas(Modifier.fillMaxSize()) {
                        val fit = fitted
                        val frame = singleFrame
                        val totalVisualScale = visualScaleDelta * trashVisualScale
                        val drawOverlay = shownOverlay.copy(alpha = (shownOverlay.alpha * trashVisualAlpha).coerceIn(0f, 1f))
                        if (drawOverlay.alpha > 0f && !isTextBlank) {
                            if (isSingleOverlay && fit != null && liveCenterCanvas != null &&
                                (abs(totalVisualScale - 1f) > 0.001f || abs(visualRotateDelta) > 0.01f)
                            ) {
                                clipRect(
                                    left = fit.left,
                                    top = fit.top,
                                    right = fit.left + fit.width,
                                    bottom = fit.top + fit.height,
                                ) {
                                    scale(scaleX = totalVisualScale, scaleY = totalVisualScale, pivot = liveCenterCanvas) {
                                        rotate(degrees = visualRotateDelta, pivot = liveCenterCanvas) {
                                            val sx = fit.width / photo.width.coerceAtLeast(1)
                                            val sy = fit.height / photo.height.coerceAtLeast(1)
                                            translate(fit.left, fit.top) {
                                                scale(scaleX = sx, scaleY = sy, pivot = Offset.Zero) {
                                                    drawWatermarkTiles(
                                                        cell = drawOverlay.cell,
                                                        tileMode = drawOverlay.tileMode,
                                                        destWidth = photo.width.toFloat(),
                                                        destHeight = photo.height.toFloat(),
                                                        offsetX = drawOverlay.offsetX,
                                                        offsetY = drawOverlay.offsetY,
                                                        alpha = drawOverlay.alpha,
                                                        tileWidth = drawOverlay.logicalWidth,
                                                        tileHeight = drawOverlay.logicalHeight,
                                                        drawWidth = drawOverlay.drawWidth,
                                                        drawHeight = drawOverlay.drawHeight,
                                                    )
                                                }
                                            }
                                        }
                                    }
                                }
                            } else {
                                drawLiveOverlayLayer(photo.width, photo.height, drawOverlay)
                            }
                        }
                        if (isSingleOverlay && fit != null && (snapX || snapY)) {
                            clipRect(
                                left = fit.left,
                                top = fit.top,
                                right = fit.left + fit.width,
                                bottom = fit.top + fit.height,
                            ) {
                                val guideHalo = Color.Black.copy(alpha = 0.30f)
                                val guideFill = selectionColor.copy(alpha = 0.88f)
                                val guideHaloW = 2.dp.toPx() / zoom
                                val guideW = 1.dp.toPx() / zoom
                                if (snapX) {
                                    val cx = fit.left + fit.width / 2f
                                    drawLine(guideHalo, Offset(cx, fit.top), Offset(cx, fit.top + fit.height), strokeWidth = guideHaloW)
                                    drawLine(guideFill, Offset(cx, fit.top), Offset(cx, fit.top + fit.height), strokeWidth = guideW)
                                }
                                if (snapY) {
                                    val cy = fit.top + fit.height / 2f
                                    drawLine(guideHalo, Offset(fit.left, cy), Offset(fit.left + fit.width, cy), strokeWidth = guideHaloW)
                                    drawLine(guideFill, Offset(fit.left, cy), Offset(fit.left + fit.width, cy), strokeWidth = guideW)
                                }
                            }
                        }
                        val hit = selected
                        if (hit != null && fit != null && shownOverlay.alpha > 0.01f && !isTextBlank) {
                            if (shownOverlay.tileMode == WatermarkTileMode.REPEAT) {
                                // Whole-layer: hairline + corner ticks; no handles on canvas.
                                val stroke = Stroke(width = 1.dp.toPx() / zoom)
                                val left = fit.left
                                val top = fit.top
                                val right = fit.left + fit.width
                                val bottom = fit.top + fit.height
                                drawRect(
                                    selectionColor.copy(alpha = 0.6f),
                                    Offset(left, top),
                                    Size(fit.width, fit.height),
                                    style = stroke,
                                )
                                val tick = 12.dp.toPx() / zoom
                                val tickW = 1.5.dp.toPx() / zoom
                                fun corner(x0: Float, y0: Float, dx: Float, dy: Float) {
                                    drawLine(selectionColor, Offset(x0, y0), Offset(x0 + dx, y0), strokeWidth = tickW)
                                    drawLine(selectionColor, Offset(x0, y0), Offset(x0, y0 + dy), strokeWidth = tickW)
                                }
                                corner(left, top, tick, tick)
                                corner(right, top, -tick, tick)
                                corner(left, bottom, tick, -tick)
                                corner(right, bottom, -tick, -tick)
                            } else if (frame != null) {
                                val contrastHalo = Color.Black.copy(alpha = 0.34f)
                                val bracketHalo = Color.Black.copy(alpha = 0.42f)
                                val topLeftLocal = Offset(
                                    frame.centerCanvas.x - frame.halfW,
                                    frame.centerCanvas.y - frame.halfH,
                                )
                                val frameSize = Size(frame.halfW * 2f, frame.halfH * 2f)
                                val rectLeft = topLeftLocal.x
                                val rectTop = topLeftLocal.y
                                val rectRight = rectLeft + frameSize.width
                                val rectBottom = rectTop + frameSize.height
                                val tick = min(
                                    8.dp.toPx() / zoom,
                                    min(frame.halfW, frame.halfH) * 0.45f,
                                )
                                val haloW = 2.2.dp.toPx() / zoom
                                val lineW = 1.dp.toPx() / zoom
                                val tickHaloW = 3.dp.toPx() / zoom
                                val tickW = 2.dp.toPx() / zoom
                                rotate(degrees = frame.degree, pivot = frame.centerCanvas) {
                                    drawRect(contrastHalo, topLeftLocal, frameSize, style = Stroke(width = haloW))
                                    drawRect(
                                        selectionColor.copy(alpha = 0.92f),
                                        topLeftLocal,
                                        frameSize,
                                        style = Stroke(width = lineW),
                                    )
                                    // Free-corner architectural L-brackets (bottomRight hosts the scale+rotate handle).
                                    fun cornerBracket(x0: Float, y0: Float, dx: Float, dy: Float) {
                                        drawLine(bracketHalo, Offset(x0, y0), Offset(x0 + dx, y0), strokeWidth = tickHaloW, cap = StrokeCap.Square)
                                        drawLine(bracketHalo, Offset(x0, y0), Offset(x0, y0 + dy), strokeWidth = tickHaloW, cap = StrokeCap.Square)
                                        drawLine(selectionColor, Offset(x0, y0), Offset(x0 + dx, y0), strokeWidth = tickW, cap = StrokeCap.Square)
                                        drawLine(selectionColor, Offset(x0, y0), Offset(x0, y0 + dy), strokeWidth = tickW, cap = StrokeCap.Square)
                                    }
                                    cornerBracket(rectLeft, rectTop, tick, tick)
                                    cornerBracket(rectRight, rectTop, -tick, tick)
                                    cornerBracket(rectLeft, rectBottom, tick, -tick)
                                    if (transforming) {
                                        val cross = 5.dp.toPx() / zoom
                                        val crossHaloW = 2.dp.toPx() / zoom
                                        val crossW = 1.dp.toPx() / zoom
                                        drawLine(
                                            bracketHalo,
                                            Offset(frame.centerCanvas.x - cross, frame.centerCanvas.y),
                                            Offset(frame.centerCanvas.x + cross, frame.centerCanvas.y),
                                            strokeWidth = crossHaloW,
                                        )
                                        drawLine(
                                            bracketHalo,
                                            Offset(frame.centerCanvas.x, frame.centerCanvas.y - cross),
                                            Offset(frame.centerCanvas.x, frame.centerCanvas.y + cross),
                                            strokeWidth = crossHaloW,
                                        )
                                        drawLine(
                                            selectionColor.copy(alpha = 0.85f),
                                            Offset(frame.centerCanvas.x - cross, frame.centerCanvas.y),
                                            Offset(frame.centerCanvas.x + cross, frame.centerCanvas.y),
                                            strokeWidth = crossW,
                                        )
                                        drawLine(
                                            selectionColor.copy(alpha = 0.85f),
                                            Offset(frame.centerCanvas.x, frame.centerCanvas.y - cross),
                                            Offset(frame.centerCanvas.x, frame.centerCanvas.y + cross),
                                            strokeWidth = crossW,
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
                Box(Modifier.fillMaxSize().testTag(if (selected == null) "previewViewTarget" else "previewWatermarkTarget")
                    .pointerInput(Unit) {
                        awaitPointerEventScope {
                            while (true) {
                                val event = awaitPointerEvent()
                                if (event.type != PointerEventType.Scroll || dragging || transforming) continue
                                val change = event.changes.firstOrNull() ?: continue
                                interruptReset()
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
                        awaitEachGesture {
                            val firstDown = awaitFirstDown(requireUnconsumed = false)
                            interruptReset()
                            if (transforming || handleTransformActive || (draft?.stage ?: 0) > 0) {
                                firstDown.consume()
                                return@awaitEachGesture
                            }
                            val fit = computeFittedImageRect(size.width.toFloat(), size.height.toFloat(), photo.width.toFloat(), photo.height.toFloat()) ?: return@awaitEachGesture
                            val boxSize = Size(size.width.toFloat(), size.height.toFloat())
                            val config = current.config
                            val startOverlay = currentOverlay
                            val repeat = startOverlay.tileMode == WatermarkTileMode.REPEAT
                            val origin = Offset(startOverlay.offsetX * photo.width, startOverlay.offsetY * photo.height)
                            val cellSize = Size(startOverlay.logicalWidth.toFloat(), startOverlay.logicalHeight.toFloat())
                            val drawW = startOverlay.drawWidth
                                .coerceIn(1, startOverlay.logicalWidth.coerceAtLeast(1))
                                .toFloat()
                            val drawH = startOverlay.drawHeight
                                .coerceIn(1, startOverlay.logicalHeight.coerceAtLeast(1))
                                .toFloat()
                            val padX = (cellSize.width - drawW) / 2f
                            val padY = (cellSize.height - drawH) / 2f
                            val pixels by lazy(LazyThreadSafetyMode.NONE) { startOverlay.cell.toPixelMap() }
                            fun snapEnterPhotoPx(): Float = 5.dp.toPx() * photo.width / fit.width / zoom
                            fun snapExitPhotoPx(): Float = 9.dp.toPx() * photo.width / fit.width / zoom
                            fun hitAt(position: Offset): PreviewCellHit? {
                                if (startOverlay.alpha <= .01f ||
                                    (config.markMode == WatermarkMode.Text && config.text.isBlank())
                                ) return null
                                val normalized = photoPoint(position, boxSize, fit, zoom, pan)
                                val point = Offset(normalized.x * photo.width, normalized.y * photo.height)
                                if (!repeat) {
                                    val cellCenter = origin + Offset(cellSize.width / 2f, cellSize.height / 2f)
                                    val photoPerView = photo.width / fit.width / zoom
                                    val minHalf = 16.dp.toPx() * photoPerView
                                    val slop = 12.dp.toPx() * photoPerView
                                    return if (
                                        hitSingleWatermark(
                                            point = point,
                                            photo = Size(photo.width.toFloat(), photo.height.toFloat()),
                                            cellCenter = cellCenter,
                                            contentWidth = startOverlay.contentWidth,
                                            contentHeight = startOverlay.contentHeight,
                                            degree = config.degree,
                                            minHalfSize = minHalf,
                                            slop = slop,
                                        )
                                    ) {
                                        PreviewCellHit(origin, 0, 0)
                                    } else {
                                        null
                                    }
                                }
                                val candidate = previewCellHit(point, Size(photo.width.toFloat(), photo.height.toFloat()), cellSize, origin, true)
                                val pxMap = pixels
                                val pxScaleX = pxMap.width.toFloat() / drawW
                                val pxScaleY = pxMap.height.toFloat() / drawH
                                // Transparent gap is photo space; keep a bounded margin for small text.
                                return candidate?.takeIf {
                                    val local = point - it.origin
                                    val localPxX = (local.x - padX) * pxScaleX
                                    val localPxY = (local.y - padY) * pxScaleY
                                    // Check the whole visible touch margin: sparse samples miss thin glyphs.
                                    val radius = 12.dp.toPx() * photo.width / fit.width / zoom
                                    val radiusPxX = radius * pxScaleX
                                    val radiusPxY = radius * pxScaleY
                                    val xs = ceil(localPxX - radiusPxX).toInt().coerceAtLeast(0)..
                                        floor(localPxX + radiusPxX).toInt().coerceAtMost(pxMap.width - 1)
                                    val ys = ceil(localPxY - radiusPxY).toInt().coerceAtLeast(0)..
                                        floor(localPxY + radiusPxY).toInt().coerceAtMost(pxMap.height - 1)
                                    val inDirectBounds =
                                        localPxX >= 0f && localPxX < pxMap.width.toFloat() &&
                                            localPxY >= 0f && localPxY < pxMap.height.toFloat()
                                    val hitDirectly = inDirectBounds &&
                                        pxMap[localPxX.toInt(), localPxY.toInt()].alpha > .05f
                                    hitDirectly || ys.any { y -> xs.any { x ->
                                        val dxPhoto = (x - localPxX) / pxScaleX
                                        val dyPhoto = (y - localPxY) / pxScaleY
                                        dxPhoto * dxPhoto + dyPhoto * dyPhoto <= radius * radius && pxMap[x, y].alpha > .05f
                                    } }
                                }
                            }
                            val down = firstDown
                            val wasSelected = selected != null
                            var activeTarget: PreviewCellHit? = if (repeat) {
                                selected?.let { it.copy(origin = origin + Offset(it.column * cellSize.width, it.row * cellSize.height)) }
                            } else {
                                hitAt(down.position)
                                    ?: currentEvent.changes.filter { it.pressed }.firstNotNullOfOrNull { hitAt(it.position) }
                            }
                            renderFailed = false
                            down.consume()
                            val startId = current.identity
                            var scale = 1f
                            var rawRotation = 0f
                            var dragCenter = (activeTarget?.origin ?: Offset.Zero) + Offset(cellSize.width / 2f, cellSize.height / 2f)
                            var rawDragCenter = dragCenter
                            var moved = false
                            var multi = false
                            var hasTranslated = false
                            var hasRotated = false
                            var hasTransformed = false
                            var cancelled = false
                            var lastPosition = down.position
                            val initialPointerCount = currentEvent.changes.count { it.pressed }
                            val gestureBench = ClampDragBench.previewScope("preview_gesture")
                            var addedPointerDelayMs: Long? = null
                            dragging = true
                            showTrashZone = false
                            overTrash = false
                            snapX = false
                            snapY = false
                            angleSnapped = false
                            gestureMultiTransform = false
                            try {
                                do {
                                    val event = awaitPointerEvent()
                                    if (event.changes.any { it.isConsumed }) { cancelled = true; break }
                                    val added = event.changes.firstOrNull { it.pressed && !it.previousPressed }
                                    if (added != null) {
                                        addedPointerDelayMs = added.uptimeMillis - firstDown.uptimeMillis
                                        if (!repeat && !moved && activeTarget == null) {
                                            val secondHit = event.changes
                                                .filter { it.pressed && !it.previousPressed }
                                                .firstNotNullOfOrNull { hitAt(it.position) }
                                            if (secondHit != null) {
                                                activeTarget = secondHit
                                                val initCenter = secondHit.origin + Offset(cellSize.width / 2f, cellSize.height / 2f)
                                                dragCenter = initCenter
                                                rawDragCenter = initCenter
                                            }
                                        }
                                    }
                                    val pressed = event.changes.count { it.pressed }
                                    if (pressed > 1) { multi = true }
                                    val deltaPan = event.calculatePan()
                                    val deltaZoom = event.calculateZoom()
                                    val deltaRotation = event.calculateRotation()
                                    if (pressed > 0) {
                                        lastPosition = event.changes.firstOrNull { it.pressed }?.position
                                            ?: event.changes.first().position
                                        val stepTranslated = deltaPan != Offset.Zero
                                        val stepRotated = multi && abs(deltaRotation) > 0.001f
                                        val stepScaled = multi && abs(deltaZoom - 1f) > 0.0001f
                                        hasTranslated = hasTranslated || stepTranslated
                                        hasRotated = hasRotated || stepRotated
                                        hasTransformed = hasTransformed || stepTranslated || stepRotated || stepScaled
                                        moved = moved || (lastPosition - down.position).getDistance() > viewConfiguration.touchSlop || multi
                                        if (moved) {
                                            val target = activeTarget
                                            if (target != null) {
                                                val safeStartSize = config.textSize.coerceAtLeast(WatermarkConfigRules.MIN_TEXT_SIZE)
                                                scale = (scale * deltaZoom).coerceIn(
                                                    WatermarkConfigRules.MIN_TEXT_SIZE / safeStartSize,
                                                    WatermarkConfigRules.MAX_TEXT_SIZE / safeStartSize,
                                                )
                                                rawRotation += deltaRotation
                                                rawDragCenter += Offset(
                                                    (deltaPan.x / zoom) * photo.width / fit.width,
                                                    (deltaPan.y / zoom) * photo.height / fit.height,
                                                )
                                                val nextTextSize = if (multi) {
                                                    (safeStartSize * scale).coerceIn(
                                                        WatermarkConfigRules.MIN_TEXT_SIZE,
                                                        WatermarkConfigRules.MAX_TEXT_SIZE,
                                                    )
                                                } else {
                                                    config.textSize
                                                }
                                                val nextDegree = if (multi && hasRotated) {
                                                    if (!repeat) {
                                                        val snap = snapWatermarkDegree(config.degree + rawRotation, angleSnapped)
                                                        if (snap.snapped && !angleSnapped) {
                                                            currentHaptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                                        }
                                                        angleSnapped = snap.snapped
                                                        snap.degree
                                                    } else {
                                                        normalizeDegree360(config.degree + rawRotation)
                                                    }
                                                } else {
                                                    config.degree
                                                }
                                                if (!repeat && multi && hasTransformed) {
                                                    gestureMultiTransform = true
                                                    hudSizeRatio = nextTextSize / safeStartSize
                                                    hudDegree = nextDegree
                                                }
                                                val next = config.copy(
                                                    textSize = nextTextSize,
                                                    degree = nextDegree,
                                                )
                                                if (!repeat) {
                                                    val sizeRatio = (nextTextSize / safeStartSize).coerceAtLeast(0.01f)
                                                    val estContentW = startOverlay.contentWidth * sizeRatio
                                                    val estContentH = startOverlay.contentHeight * sizeRatio
                                                    rawDragCenter = clampSingleWatermarkCenter(
                                                        center = rawDragCenter,
                                                        photoSize = photoSize,
                                                        contentWidth = estContentW,
                                                        contentHeight = estContentH,
                                                        degree = next.degree,
                                                    )
                                                    if (!multi && hasTransformed) {
                                                        showTrashZone = true
                                                        val nextOverTrash = isInBottomTrashZone(
                                                            fingerView = lastPosition,
                                                            boxSize = size,
                                                            zoneCenterYOffsetPx = 40.dp.toPx(),
                                                            hitRadiusPx = 42.dp.toPx(),
                                                        )
                                                        if (nextOverTrash && !overTrash) {
                                                            currentHaptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                                        }
                                                        overTrash = nextOverTrash
                                                    } else {
                                                        showTrashZone = false
                                                        overTrash = false
                                                    }
                                                    if (overTrash) {
                                                        snapX = false
                                                        snapY = false
                                                        dragCenter = rawDragCenter
                                                    } else if (hasTranslated) {
                                                        val snapPos = snapSingleWatermarkCenter(
                                                            rawCenter = rawDragCenter,
                                                            photoSize = photoSize,
                                                            wasSnappedX = snapX,
                                                            wasSnappedY = snapY,
                                                            enterThresholdPx = snapEnterPhotoPx(),
                                                            exitThresholdPx = snapExitPhotoPx(),
                                                        )
                                                        if ((snapPos.snappedX && !snapX) || (snapPos.snappedY && !snapY)) {
                                                            currentHaptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                                        }
                                                        snapX = snapPos.snappedX
                                                        snapY = snapPos.snappedY
                                                        dragCenter = clampSingleWatermarkCenter(
                                                            center = snapPos.center,
                                                            photoSize = photoSize,
                                                            contentWidth = estContentW,
                                                            contentHeight = estContentH,
                                                            degree = next.degree,
                                                        )
                                                    } else {
                                                        snapX = false
                                                        snapY = false
                                                        dragCenter = rawDragCenter
                                                    }
                                                } else {
                                                    dragCenter = rawDragCenter
                                                }
                                                if (hasTransformed) {
                                                    draft = PreviewDraft(config, next, dragCenter, target, repeat, photoSize)
                                                }
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
                                    if (!repeat && !multi && activeTarget != null && moved && overTrash) {
                                        draft = null
                                        raster = null
                                        selected = null
                                        transforming = false
                                        handleTransformActive = false
                                        current.onClear()
                                    } else if (activeTarget != null && moved && committed != null) {
                                        draft = committed.copy(stage = 1)
                                        if (!repeat && !wasSelected) {
                                            selected = null
                                        }
                                    } else if (!moved && !multi) {
                                        val tapped = hitAt(down.position)
                                        val prev = selected
                                        selected = tapped
                                        if (prev != null && tapped != null && config.markMode == WatermarkMode.Text) {
                                            current.onEditText()
                                        }
                                    }
                                } else { draft = null; raster = null }
                            } finally {
                                dragging = false
                                showTrashZone = false
                                overTrash = false
                                snapX = false
                                snapY = false
                                angleSnapped = false
                                gestureMultiTransform = false
                                gestureBench.finish(mapOf("target" to if (activeTarget == null) "view" else "watermark",
                                    "initialPointers" to initialPointerCount, "addedDelayMs" to addedPointerDelayMs))
                                if (current.identity != startId || cancelled || draft?.stage == 0) { draft = null; raster = null }
                            }
                        }
                    })
                val frame = singleFrame
                if (frame != null && box.width > 0) {
                    val viewCenter = Offset(box.width / 2f, box.height / 2f)
                    fun toView(canvasOffset: Offset): Offset = (canvasOffset - viewCenter) * zoom + viewCenter + pan
                    val margin = with(density) { 22.dp.toPx() }
                    val maxX = (box.width - margin).coerceAtLeast(margin)
                    val maxY = (box.height - margin).coerceAtLeast(margin)
                    fun clampHandle(p: Offset) = Offset(p.x.coerceIn(margin, maxX), p.y.coerceIn(margin, maxY))
                    val transformAt = clampHandle(toView(frame.bottomRightCanvas))
                    val cellCenterView = toView(frame.centerCanvas)
                    val finishHandleTransform: (Boolean) -> Unit = { cancelled ->
                        if (!cancelled && handleTransformActive) {
                            val currentDraft = draft
                            if (currentDraft != null &&
                                currentDraft.photoSize == photoSize &&
                                currentPhotoSize == transformStartPhotoSize &&
                                current.config == currentDraft.baseConfig &&
                                currentDraft.config != currentDraft.baseConfig
                            ) {
                                draft = currentDraft.copy(stage = 1)
                            } else {
                                draft = null
                                raster = null
                            }
                        } else if (handleTransformActive) {
                            draft = null
                            raster = null
                        }
                        handleTransformActive = false
                        transforming = false
                        angleSnapped = false
                    }
                    SelectionHandle(
                        center = transformAt,
                        tag = "previewSelectionRotate",
                        icon = painterResource(Res.drawable.ic_func_angle),
                        description = stringResource(Res.string.preview_transform_handle),
                        container = if (transforming) selectionColor else chromeColor.copy(alpha = 0.92f),
                        border = selectionColor,
                        tint = if (transforming) MaterialTheme.colorScheme.onPrimary else selectionColor,
                        iconSize = 14.dp,
                        modifier = Modifier.align(AbsoluteAlignment.TopLeft),
                        gestureLocked = dragging,
                        onTransform = { finger, dragStart ->
                            if (dragStart) {
                                val hit = selected
                                if (dragging || (draft?.stage ?: 0) > 0 || hit == null) {
                                    handleTransformActive = false
                                } else {
                                    interruptReset()
                                    val minStartDist = with(density) { 24.dp.toPx() }
                                    transformPivot = cellCenterView
                                    transformLastAngle = atan2(finger.y - cellCenterView.y, finger.x - cellCenterView.x)
                                    transformStartDist = hypot(finger.x - cellCenterView.x, finger.y - cellCenterView.y)
                                        .coerceAtLeast(minStartDist)
                                    transformStartSize = current.config.textSize
                                        .coerceAtLeast(WatermarkConfigRules.MIN_TEXT_SIZE)
                                    transformRawDegree = current.config.degree
                                    transformBaseConfig = current.config
                                    transformStartPhotoSize = photoSize
                                    transformStartCenterPhoto = liveCenterPhoto
                                    transformStartContentW = shownContentW
                                    transformStartContentH = shownContentH
                                    transformHit = hit
                                    angleSnapped = false
                                    hudSizeRatio = 1f
                                    hudDegree = normalizeDegree360(current.config.degree)
                                    transforming = true
                                    handleTransformActive = true
                                }
                            } else if (handleTransformActive) {
                                if (current.config != transformBaseConfig || currentPhotoSize != transformStartPhotoSize) {
                                    draft = null
                                    raster = null
                                    handleTransformActive = false
                                    transforming = false
                                    angleSnapped = false
                                } else {
                                    val dx = finger.x - transformPivot.x
                                    val dy = finger.y - transformPivot.y
                                    val dist = hypot(dx, dy)
                                    if (dist >= 4f) {
                                        val angle = atan2(dy, dx)
                                        var step = angle - transformLastAngle
                                        val pi = PI.toFloat()
                                        while (step > pi) step -= 2f * pi
                                        while (step < -pi) step += 2f * pi
                                        transformLastAngle = angle
                                        transformRawDegree += step * 180f / pi
                                    }
                                    val snap = snapWatermarkDegree(transformRawDegree, angleSnapped)
                                    if (snap.snapped && !angleSnapped) {
                                        currentHaptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                    }
                                    angleSnapped = snap.snapped
                                    val nextSize = (transformStartSize * (dist / transformStartDist))
                                        .coerceIn(WatermarkConfigRules.MIN_TEXT_SIZE, WatermarkConfigRules.MAX_TEXT_SIZE)
                                    val scaleRatio = (nextSize / transformStartSize).coerceAtLeast(0.01f)
                                    val clampedCenter = clampSingleWatermarkCenter(
                                        center = transformStartCenterPhoto,
                                        photoSize = photoSize,
                                        contentWidth = transformStartContentW * scaleRatio,
                                        contentHeight = transformStartContentH * scaleRatio,
                                        degree = snap.degree,
                                    )
                                    val nextConfig = transformBaseConfig.copy(
                                        textSize = nextSize,
                                        degree = snap.degree,
                                    )
                                    hudSizeRatio = nextSize / transformStartSize
                                    hudDegree = snap.degree
                                    val hit = transformHit
                                    if (hit != null) {
                                        draft = PreviewDraft(
                                            baseConfig = transformBaseConfig,
                                            config = nextConfig,
                                            center = clampedCenter,
                                            hit = hit,
                                            repeat = false,
                                            photoSize = photoSize,
                                        )
                                    }
                                }
                            }
                        },
                        onTransformEnd = finishHandleTransform,
                    )
                }
                val activeHudFrame = hudFrame
                if (activeHudFrame != null && box.width > 0) {
                    val viewCenter = Offset(box.width / 2f, box.height / 2f)
                    fun toViewHud(canvasOffset: Offset): Offset = (canvasOffset - viewCenter) * zoom + viewCenter + pan
                    val cellCenterView = toViewHud(activeHudFrame.centerCanvas)
                    val viewCorners = listOf(
                        toViewHud(activeHudFrame.topLeftCanvas),
                        toViewHud(activeHudFrame.topRightCanvas),
                        toViewHud(activeHudFrame.bottomLeftCanvas),
                        toViewHud(activeHudFrame.bottomRightCanvas),
                    )
                    val minCornerY = viewCorners.minOf { it.y }
                    val maxCornerY = viewCorners.maxOf { it.y }
                    val sizePct = (hudSizeRatio * 100f).roundToInt()
                    val deg = hudDegree.roundToInt().let { d -> (d % 360).let { if (it > 180) it - 360 else it } }
                    val hudShape = RoundedCornerShape(EwmTheme.shapes.chipRadiusDp)
                    val hudBg = if (angleSnapped) selectionColor.copy(alpha = 0.94f) else chromeColor.copy(alpha = 0.94f)
                    val hudBorder = if (angleSnapped) selectionColor else selectionColor.copy(alpha = 0.85f)
                    val hudTextColor = if (angleSnapped) MaterialTheme.colorScheme.onPrimary else selectionColor
                    Layout(
                        content = {
                            Text(
                                text = "${sizePct}% · ${deg}°",
                                style = MaterialTheme.typography.labelMedium.copy(
                                    fontFamily = FontFamily.Monospace,
                                    fontWeight = FontWeight.Medium,
                                    fontSize = 12.sp,
                                ),
                                color = hudTextColor,
                                maxLines = 1,
                                modifier = Modifier
                                    .background(hudBg, hudShape)
                                    .border(1.dp, hudBorder, hudShape)
                                    .padding(horizontal = 10.dp, vertical = 4.dp)
                                    .testTag("previewSelectionAngle"),
                            )
                        },
                        modifier = Modifier.align(AbsoluteAlignment.TopLeft).zIndex(3f),
                    ) { measurables, constraints ->
                        val placeable = measurables.first().measure(constraints.copy(minWidth = 0, minHeight = 0))
                        val hudW = placeable.width.toFloat()
                        val hudH = placeable.height.toFloat()
                        val hudPad = with(density) { 10.dp.toPx() }
                        val hudGap = with(density) { 12.dp.toPx() }
                        val hudX = (cellCenterView.x - hudW / 2f)
                            .coerceIn(hudPad, (box.width - hudW - hudPad).coerceAtLeast(hudPad))
                        val hudY = if (minCornerY >= hudH + hudGap + with(density) { 6.dp.toPx() }) {
                            minCornerY - hudH - hudGap
                        } else {
                            (maxCornerY + hudGap)
                                .coerceIn(hudPad, (box.height - hudH - hudPad).coerceAtLeast(hudPad))
                        }
                        layout(constraints.maxWidth, constraints.maxHeight) {
                            placeable.place(hudX.roundToInt(), hudY.roundToInt())
                        }
                    }
                }
                if (showTrashZone && !isRepeatOverlay) {
                    val trashScale by animateFloatAsState(
                        targetValue = if (overTrash) 1.12f else 1f,
                        animationSpec = tween(fadeMs),
                    )
                    val trashBg by animateColorAsState(
                        targetValue = if (overTrash) MaterialTheme.colorScheme.error else chromeColor.copy(alpha = 0.92f),
                        animationSpec = tween(fadeMs),
                    )
                    val trashBorder by animateColorAsState(
                        targetValue = if (overTrash) MaterialTheme.colorScheme.error else Color.White.copy(alpha = 0.24f),
                        animationSpec = tween(fadeMs),
                    )
                    val trashContent by animateColorAsState(
                        targetValue = if (overTrash) MaterialTheme.colorScheme.onError else Color.White.copy(alpha = 0.92f),
                        animationSpec = tween(fadeMs),
                    )
                    Box(
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .padding(bottom = 16.dp)
                            .zIndex(3f)
                            .testTag("previewDragTrashZone"),
                        contentAlignment = Alignment.Center,
                    ) {
                        Row(
                            modifier = Modifier
                                .height(48.dp)
                                .graphicsLayer {
                                    scaleX = trashScale
                                    scaleY = trashScale
                                }
                                .clip(CircleShape)
                                .background(trashBg, CircleShape)
                                .border(1.dp, trashBorder, CircleShape)
                                .padding(horizontal = 16.dp)
                                .then(if (overTrash) Modifier.testTag("previewDragTrashArmed") else Modifier),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Icon(
                                painter = painterResource(Res.drawable.ic_template_list_item_remove),
                                contentDescription = null,
                                modifier = Modifier.size(18.dp),
                                tint = trashContent,
                            )
                            Text(
                                text = stringResource(Res.string.preview_drag_to_delete),
                                style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Medium),
                                color = trashContent,
                                maxLines = 1,
                            )
                        }
                    }
                }
                if (renderFailed) Text(
                    stringResource(Res.string.dialog_save_error_render),
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.align(Alignment.BottomCenter),
                )
            }
        }
    }
}

private class PlacedOffsetRef(var value: IntOffset = IntOffset.Zero)

@Composable
private fun SelectionHandle(
    center: Offset,
    tag: String,
    icon: Painter,
    description: String,
    container: Color,
    border: Color,
    tint: Color,
    iconSize: androidx.compose.ui.unit.Dp,
    modifier: Modifier = Modifier,
    gestureLocked: Boolean = false,
    onClick: (() -> Unit)? = null,
    onTransform: ((finger: Offset, dragStart: Boolean) -> Unit)? = null,
    onTransformEnd: ((cancelled: Boolean) -> Unit)? = null,
) {
    val centerState = rememberUpdatedState(center)
    val lockedState = rememberUpdatedState(gestureLocked)
    val transformState = rememberUpdatedState(onTransform)
    val transformEndState = rememberUpdatedState(onTransformEnd)
    val placedTopLeft = remember { PlacedOffsetRef() }
    val handleShape = RoundedCornerShape(EwmTheme.shapes.chipRadiusDp)
    Box(
        modifier
            .zIndex(2f)
            .absoluteOffset {
                val half = 22.dp.roundToPx()
                val at = centerState.value
                IntOffset(at.x.roundToInt() - half, at.y.roundToInt() - half).also {
                    placedTopLeft.value = it
                }
            }
            .size(44.dp)
            .testTag(tag)
            .then(
                if (onTransform != null) {
                    Modifier.pointerInput(Unit) {
                        awaitEachGesture {
                            val down = awaitFirstDown(requireUnconsumed = false)
                            if (lockedState.value) return@awaitEachGesture
                            val startTopLeft = placedTopLeft.value
                            val downAbs = Offset(startTopLeft.x.toFloat(), startTopLeft.y.toFloat()) + down.position
                            var started = false
                            var completed = false
                            try {
                                val drag = awaitTouchSlopOrCancellation(down.id) { change, _ ->
                                    change.consume()
                                }
                                if (drag != null) {
                                    transformState.value?.invoke(downAbs, true)
                                    started = true
                                    val dragTopLeft = placedTopLeft.value
                                    transformState.value?.invoke(
                                        Offset(dragTopLeft.x.toFloat(), dragTopLeft.y.toFloat()) + drag.position,
                                        false,
                                    )
                                    drag.consume()
                                    completed = drag(drag.id) { change ->
                                        val topLeft = placedTopLeft.value
                                        transformState.value?.invoke(
                                            Offset(topLeft.x.toFloat(), topLeft.y.toFloat()) + change.position,
                                            false,
                                        )
                                        change.consume()
                                    }
                                }
                            } finally {
                                if (started) {
                                    transformEndState.value?.invoke(!completed)
                                }
                            }
                        }
                    }
                } else {
                    Modifier.clickable { onClick?.invoke() }
                },
            ),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .size(24.dp)
                .clip(handleShape)
                .background(container, handleShape)
                .border(1.dp, border, handleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, description, Modifier.size(iconSize), tint = tint)
        }
    }
}
