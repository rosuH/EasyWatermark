package me.rosuh.easywatermark.ui

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/**
 * Pure math for long-press drag-select edge auto-scroll.
 *
 * `detectDragGesturesAfterLongPress` only emits while the finger travels. A finger parked in
 * the bottom band produces no further events, so auto-scroll has to be frame-driven off this
 * ratio instead of piggybacking on pointer deltas (the old per-event `scrollBy` both stalled
 * at the edge and stuttered from overlapping scroll sessions).
 */
internal object GalleryDragAutoScroll {

    /** Height of the top/bottom band that arms auto-scroll. */
    const val EDGE_DP: Float = 84f

    /** Speed reached when the pointer sits at the very edge of the viewport. */
    const val MAX_SPEED_DP_PER_SEC: Float = 2200f

    /** Speed floor at the band threshold so entering the band always moves the grid. */
    const val MIN_SPEED_FRACTION: Float = 0.12f

    /** Longest honoured frame delta, so a stalled frame cannot teleport the grid. */
    const val MAX_FRAME_SECONDS: Float = 1f / 20f

    /**
     * Floor between selection ticks. At full auto-scroll speed cells cross the finger every
     * frame, and a tick per frame reads as a continuous buzz rather than feedback.
     */
    val MIN_TICK_INTERVAL: Duration = 45.milliseconds

    /**
     * Signed fraction of [MAX_SPEED_DP_PER_SEC] to travel: negative scrolls towards the top.
     * Returns `0` when the pointer is outside both edge bands.
     */
    fun speedFraction(pointerY: Float, viewportHeight: Float, edgePx: Float): Float {
        if (viewportHeight <= 0f || edgePx <= 0f) return 0f
        val raw = when {
            pointerY < edgePx -> (pointerY / edgePx) - 1f
            pointerY > viewportHeight - edgePx ->
                (pointerY - (viewportHeight - edgePx)) / edgePx

            else -> 0f
        }
        if (raw == 0f) return 0f
        val magnitude = abs(raw).coerceIn(0f, 1f)
        val eased = MIN_SPEED_FRACTION + (1f - MIN_SPEED_FRACTION) * magnitude
        return if (raw < 0f) -eased else eased
    }
}

/**
 * Emits only the cells whose selection actually changes when the painted range moves from
 * [previous] to [next] around [anchor].
 *
 * Re-setting the whole range on every pointer event was O(range): dragging past a few hundred
 * cells re-visited all of them ~60 times a second.
 */
internal inline fun forEachDragRangeDelta(
    anchor: Int,
    previous: Int,
    next: Int,
    onChanged: (index: Int, selected: Boolean) -> Unit,
) {
    val newLo = min(anchor, next)
    val newHi = max(anchor, next)
    val oldLo = min(anchor, previous)
    val oldHi = max(anchor, previous)
    for (i in oldLo until newLo) onChanged(i, false)
    for (i in newHi + 1..oldHi) onChanged(i, false)
    for (i in newLo until oldLo) onChanged(i, true)
    for (i in oldHi + 1..newHi) onChanged(i, true)
}
