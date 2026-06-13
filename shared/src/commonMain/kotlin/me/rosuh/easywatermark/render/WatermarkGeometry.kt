package me.rosuh.easywatermark.render

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Pure-Kotlin watermark **cell geometry** — the portable core of the rendering engine
 * (CMP plan D4/C2: "the portable core is small… one composition rule + one scale rule").
 *
 * Extracted faithfully from the Android `WaterMarkImageView` companion math
 * (`adjustHorizonalGap`/`adjustVerticalGap`/`calculateMaxSize` + the rotated-cell AABB) so the
 * future commonMain renderer (C2) reuses identical formulas on Android, JVM/desktop and iOS.
 *
 * NOTE: this is the verified-correct foundation only — it is NOT yet wired into the live Android
 * renderer. That swap (making `WaterMarkImageView`/export delegate here) is C2a and is gated
 * behind the golden image harness (C1.7), because it touches the product-core render path.
 */
object WatermarkGeometry {

    /** Cell size expanded by the horizontal gap percent: 0 → 1× (adjacent), 100 → 2×. */
    fun horizontalGap(maxSize: Int, hGapPercent: Int): Int =
        (maxSize * ((hGapPercent / 100f) + 1)).toInt()

    /** Cell size expanded by the vertical gap percent. */
    fun verticalGap(maxSize: Int, vGapPercent: Int): Int =
        (maxSize * ((vGapPercent / 100f) + 1)).toInt()

    /**
     * Diagonal length of a w×h cell — the icon cell is laid out as a square of this side.
     * Uses `pow(2)` (not `w*w`) to stay byte-identical to the Android `calculateMaxSize` it
     * replaces, so the C2a icon-path delegation is behavior-preserving.
     */
    fun diagonal(w: Float, h: Float): Int =
        sqrt(w.pow(2) + h.pow(2)).toInt()

    /**
     * Maps an arbitrary 0..360 watermark rotation `degree` to the acute reference angle (radians)
     * used to size the rotated cell's axis-aligned bounding box. Mirrors the original `when`:
     * `0..90 → d`, `90..270 → |180 − d|`, else `360 − d`.
     */
    fun normalizedRadians(degree: Float): Double {
        val d = degree.toDouble()
        val ref = when {
            d in 0.0..90.0 -> d
            d in 90.0..270.0 -> abs(180.0 - d)
            else -> 360.0 - d
        }
        return ref * (PI / 180.0)
    }

    /** Width of the AABB of a `contentWidth × contentHeight` cell rotated by `degree`. */
    fun rotatedCellWidth(contentWidth: Float, contentHeight: Float, degree: Float): Float {
        val r = normalizedRadians(degree)
        return (contentWidth * cos(r) + contentHeight * sin(r)).toFloat()
    }

    /** Height of the AABB of a `contentWidth × contentHeight` cell rotated by `degree`. */
    fun rotatedCellHeight(contentWidth: Float, contentHeight: Float, degree: Float): Float {
        val r = normalizedRadians(degree)
        return (contentWidth * sin(r) + contentHeight * cos(r)).toFloat()
    }

    /** Per-axis export scale (x, y). */
    data class ExportScale(val x: Float, val y: Float)

    /**
     * The export **scale rule** (CMP plan D4): export scale = inverse of the preview fit-transform's
     * per-axis scale, computed **independently per axis**.
     *
     * This is the *corrected* rule. The current Android code (`MainViewModel.kt:325-326`) derives
     * BOTH axes from `MSCALE_X` (`scaleY = 1/MSCALE_X` — a latent bug, invisible for uniform
     * fit-center scaling but wrong for non-uniform/resizable surfaces like a Desktop window). The
     * C2b renderer wires this; until then this is the verified target, not yet driving export.
     */
    fun exportScale(previewScaleX: Float, previewScaleY: Float): ExportScale =
        ExportScale(x = 1f / previewScaleX, y = 1f / previewScaleY)
}
