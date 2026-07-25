package me.rosuh.easywatermark.ui

import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import me.rosuh.easywatermark.data.model.WatermarkTileMode
import kotlin.math.min

/**
 * C4.4R.1 — internal fitted-image CLAMP preview drag geometry + thin pointer adapter.
 *
 * Persistence stays with the host → [me.rosuh.easywatermark.session.WatermarkSessionViewModel.applyOffset].
 * This module never owns Session/repo state; it emits at most one final offset pair per successful
 * gesture for the host to commit.
 */

internal data class FittedImageRect(
    val left: Float,
    val top: Float,
    val width: Float,
    val height: Float,
)

internal data class ClampDragGestureSnapshot(
    val tileMode: WatermarkTileMode,
    val selectionIdAtStart: String,
    val selectionIdAtEnd: String,
    val startInFittedImage: Boolean,
    val startOffsetX: Float,
    val startOffsetY: Float,
    val totalDragX: Float,
    val totalDragY: Float,
    val fitted: FittedImageRect?,
    val cancelled: Boolean,
)

internal data class ClampDragCommit(
    val offsetX: Float,
    val offsetY: Float,
)

/**
 * ContentScale.Fit-style rect of [imageWidth]×[imageHeight] inside the container.
 * Returns null when any dimension is non-finite or non-positive.
 */
internal fun computeFittedImageRect(
    containerWidth: Float,
    containerHeight: Float,
    imageWidth: Float,
    imageHeight: Float,
): FittedImageRect? {
    if (!containerWidth.isFinite() || !containerHeight.isFinite() ||
        !imageWidth.isFinite() || !imageHeight.isFinite()
    ) {
        return null
    }
    if (containerWidth <= 0f || containerHeight <= 0f || imageWidth <= 0f || imageHeight <= 0f) {
        return null
    }
    val scale = min(containerWidth / imageWidth, containerHeight / imageHeight)
    val drawW = imageWidth * scale
    val drawH = imageHeight * scale
    if (!drawW.isFinite() || !drawH.isFinite() || drawW <= 0f || drawH <= 0f) return null
    return FittedImageRect(
        left = (containerWidth - drawW) / 2f,
        top = (containerHeight - drawH) / 2f,
        width = drawW,
        height = drawH,
    )
}

internal fun isPointInsideFittedImage(x: Float, y: Float, fitted: FittedImageRect): Boolean {
    if (!fitted.width.isFinite() || !fitted.height.isFinite()) return false
    if (fitted.width <= 0f || fitted.height <= 0f) return false
    return x >= fitted.left &&
        x <= fitted.left + fitted.width &&
        y >= fitted.top &&
        y <= fitted.top + fitted.height
}

/**
 * Apply a pixel drag delta to a normalized offset using **fitted** width/height only
 * (never the outer container). Result is clamped to `0f..1f`.
 */
internal fun applyClampDragDelta(
    startOffsetX: Float,
    startOffsetY: Float,
    dragDeltaX: Float,
    dragDeltaY: Float,
    fitted: FittedImageRect,
): Pair<Float, Float> {
    require(fitted.width > 0f && fitted.height > 0f) {
        "fitted dimensions must be positive"
    }
    val nx = (startOffsetX + dragDeltaX / fitted.width).coerceIn(0f, 1f)
    val ny = (startOffsetY + dragDeltaY / fitted.height).coerceIn(0f, 1f)
    return nx to ny
}

/**
 * Pure end-of-gesture decision: returns one commit or null (no host write).
 */
internal fun resolveClampDragCommit(snapshot: ClampDragGestureSnapshot): ClampDragCommit? {
    if (snapshot.cancelled) return null
    if (snapshot.tileMode != WatermarkTileMode.CLAMP) return null
    if (!snapshot.startInFittedImage) return null
    if (snapshot.selectionIdAtStart != snapshot.selectionIdAtEnd) return null
    val fitted = snapshot.fitted ?: return null
    if (!fitted.width.isFinite() || !fitted.height.isFinite()) return null
    if (fitted.width <= 0f || fitted.height <= 0f) return null
    if (snapshot.totalDragX == 0f && snapshot.totalDragY == 0f) return null
    if (!snapshot.totalDragX.isFinite() || !snapshot.totalDragY.isFinite()) return null

    val (x, y) = applyClampDragDelta(
        startOffsetX = snapshot.startOffsetX,
        startOffsetY = snapshot.startOffsetY,
        dragDeltaX = snapshot.totalDragX,
        dragDeltaY = snapshot.totalDragY,
        fitted = fitted,
    )
    return ClampDragCommit(offsetX = x, offsetY = y)
}

/**
 * Thin Compose adapter: emits at most one [onOffsetCommit] per successful end.
 * Does not call Session/repository and does not keep preview draft state.
 *
 * Selection identity is frozen at drag start; the latest [selectionId] is sampled at end
 * (via [rememberUpdatedState]) so a mid-gesture selection change yields a pure-resolver no-commit.
 *
 * @param selectionId identity string for the selected image (e.g. MediaRef value)
 * @param isClamp true when product tile mode is CLAMP/single
 * @param imageWidth displayed bitmap pixel width (for Fit rect)
 * @param imageHeight displayed bitmap pixel height
 * @param offsetX current committed/start offset (0..1)
 * @param offsetY current committed/start offset (0..1)
 * @param onOffsetCommit host-owned persistence hook (typically session.applyOffset)
 */
internal fun Modifier.clampPreviewOffsetDrag(
    enabled: Boolean,
    selectionId: String,
    isClamp: Boolean,
    imageWidth: Float,
    imageHeight: Float,
    offsetX: Float,
    offsetY: Float,
    onOffsetCommit: (offsetX: Float, offsetY: Float) -> Unit,
): Modifier = composed {
    if (!enabled || !isClamp || selectionId.isEmpty()) {
        return@composed this
    }
    val currentSelectionId by rememberUpdatedState(selectionId)
    val currentIsClamp by rememberUpdatedState(isClamp)
    val currentImageWidth by rememberUpdatedState(imageWidth)
    val currentImageHeight by rememberUpdatedState(imageHeight)
    val currentOffsetX by rememberUpdatedState(offsetX)
    val currentOffsetY by rememberUpdatedState(offsetY)
    val currentOnCommit by rememberUpdatedState(onOffsetCommit)

    // Do not key pointerInput on selectionId alone in a way that prevents end sampling:
    // freeze start id on drag start; read latest selectionId at end via currentSelectionId.
    this.pointerInput(enabled, isClamp, imageWidth, imageHeight) {
        var active = false
        var startInFitted = false
        var startId = ""
        var startOx = 0.5f
        var startOy = 0.5f
        var totalDx = 0f
        var totalDy = 0f
        var fitted: FittedImageRect? = null
        // H0.1: measurement scope for this gesture only (no product state).
        var bench: ClampDragBench.GestureScope? = null

        fun endGesture(cancelled: Boolean) {
            val scope = bench
            scope?.mark("drag")
            val tile =
                if (currentIsClamp) WatermarkTileMode.CLAMP else WatermarkTileMode.REPEAT
            val commit = resolveClampDragCommit(
                ClampDragGestureSnapshot(
                    tileMode = tile,
                    selectionIdAtStart = startId,
                    // Latest selection at end — not the start-time capture.
                    selectionIdAtEnd = currentSelectionId,
                    startInFittedImage = startInFitted,
                    startOffsetX = startOx,
                    startOffsetY = startOy,
                    totalDragX = totalDx,
                    totalDragY = totalDy,
                    fitted = fitted,
                    cancelled = cancelled,
                ),
            )
            scope?.mark("resolveCommit")
            if (commit != null) {
                currentOnCommit(commit.offsetX, commit.offsetY)
                // Host callback time includes applyOffset + invalidate kickoff (not full preview).
                scope?.markCommitDone()
            }
            scope?.finish(
                mapOf(
                    "cancelled" to cancelled,
                    "platform" to "shared",
                ),
            )
            bench = null
            active = false
            startInFitted = false
            totalDx = 0f
            totalDy = 0f
            fitted = null
        }

        detectDragGestures(
            onDragStart = { start: Offset ->
                fitted = computeFittedImageRect(
                    containerWidth = size.width.toFloat(),
                    containerHeight = size.height.toFloat(),
                    imageWidth = currentImageWidth,
                    imageHeight = currentImageHeight,
                )
                val f = fitted
                startInFitted = f != null && isPointInsideFittedImage(start.x, start.y, f)
                active = startInFitted && currentIsClamp
                startId = currentSelectionId
                startOx = currentOffsetX
                startOy = currentOffsetY
                totalDx = 0f
                totalDy = 0f
                // Only instrument gestures that start inside the fitted image in CLAMP.
                bench = if (active) ClampDragBench.gestureScope() else null
            },
            onDragEnd = {
                if (active || startInFitted) {
                    endGesture(cancelled = false)
                } else {
                    active = false
                    totalDx = 0f
                    totalDy = 0f
                    bench = null
                }
            },
            onDragCancel = {
                endGesture(cancelled = true)
            },
        ) { change, dragAmount ->
            if (!active) return@detectDragGestures
            change.consume()
            totalDx += dragAmount.x
            totalDy += dragAmount.y
            // Count samples only — no mid-gesture raster / live draft (H0.1 product contract).
            bench?.sample()
        }
    }
}
