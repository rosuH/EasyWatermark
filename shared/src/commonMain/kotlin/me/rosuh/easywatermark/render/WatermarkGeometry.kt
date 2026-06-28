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
 * Extracted faithfully from the legacy Android watermark cell math
 * (`adjustHorizonalGap`/`adjustVerticalGap`/`calculateMaxSize` + the rotated-cell AABB, formerly in
 * the now-retired `WaterMarkImageView`) so the future commonMain renderer (C2) reuses identical
 * formulas on Android, JVM/desktop and iOS.
 *
 * Wired in C2a: Android text and icon watermark-cell sizing delegates here for both preview
 * (`EditorScreen.WaterMarkCanvas`) and export (`MainViewModel.generateImage`). Image-space sizing
 * (S3a), the Compose Canvas preview swap (S3c-2), and `ViewInfo`/`WaterMarkImageView` retirement
 * (S3c-3) are done; the remaining C2 work is moving the COMPOSITION (drawing, tiling) itself into
 * a commonMain renderer.
 */
object WatermarkGeometry {

    /**
     * S3a image-space text-sizing reference width. `textSize` is a fraction (`textSize / REF_WIDTH`)
     * of the target image width, so the watermark is a constant fraction of the image on every
     * platform; at the reference width 1000 the size equals the legacy unscaled value
     * (`fontPx(t, 1000) == t`). Shared source for the Desktop/iOS renderers (S4d-181); Android keeps its
     * own `WatermarkRenderer.REF_WIDTH` for now (a follow-up may route Android through this too).
     */
    const val REF_WIDTH: Float = 1000f

    /**
     * Image-space font size in px: `textSize * imageWidth / REF_WIDTH` (S3a). Byte-identical to the
     * per-renderer inline formula it replaces — the dividend is `Float`, so dividing by `REF_WIDTH`
     * (1000f) equals the old `/ 1000` (Int) divisor exactly.
     */
    fun fontPx(textSize: Float, imageWidth: Int): Float = textSize * imageWidth / REF_WIDTH

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
     * Status (S4b): this rule is **latent / unwired today**. Since S3a, export sizing is image-space
     * (`textPx = textSize * imageWidth / REF_WIDTH`) and `MainViewModel.generateImage` reads NO
     * preview matrix — the old `ViewInfo` / `1/MSCALE_X` export-scale coupling was removed
     * (S3c-1/S3c-3). For the current uniform fit-center preview both axes scale identically, so this
     * helper would be a no-op; it becomes relevant only for non-uniform / resizable surfaces (e.g. a
     * Desktop window), where it is the verified target for the future commonMain renderer (C2/C4).
     * Kept as that target; it does not drive export today.
     */
    fun exportScale(previewScaleX: Float, previewScaleY: Float): ExportScale =
        ExportScale(x = 1f / previewScaleX, y = 1f / previewScaleY)
}
