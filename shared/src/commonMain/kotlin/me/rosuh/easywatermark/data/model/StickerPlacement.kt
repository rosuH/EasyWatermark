package me.rosuh.easywatermark.data.model

/**
 * Keeps a Single (CLAMP) mark fully inside the photo so apply/select is never an off-edge ghost.
 * Fractions are photo-normalized offsets used by [ImageInfo] / overlay origin.
 */
object StickerPlacement {
    /** Soft inset when cell size is unknown (built-in apply, saved sticker restore). */
    const val SAFE_MIN = 0.18f
    const val SAFE_MAX = 0.82f

    /**
     * Permissible range for user-placed live single (CLAMP) offsets. Deep edge/corner placement
     * produces negative cell-origin offsets (or offsets above 1) when rotation/gap padding extends past the edge.
     */
    const val LIVE_MIN = -5f
    const val LIVE_MAX = 2f

    fun coerce(offsetX: Float, offsetY: Float): Pair<Float, Float> =
        offsetX.coerceIn(SAFE_MIN, SAFE_MAX) to offsetY.coerceIn(SAFE_MIN, SAFE_MAX)

    /**
     * Returns null when [offsetX]/[offsetY] are already finite within [LIVE_MIN]..[LIVE_MAX]
     * (preserve user placement including corners/edges); otherwise returns coerced safe coordinates
     * within [SAFE_MIN]..[SAFE_MAX].
     */
    fun sanitizeLiveSingleOffset(offsetX: Float, offsetY: Float): Pair<Float, Float>? {
        if (offsetX.isFinite() &&
            offsetY.isFinite() &&
            offsetX in LIVE_MIN..LIVE_MAX &&
            offsetY in LIVE_MIN..LIVE_MAX
        ) {
            return null
        }
        val safeX = if (offsetX.isFinite()) offsetX else DefaultSingle.first
        val safeY = if (offsetY.isFinite()) offsetY else DefaultSingle.second
        return coerce(safeX, safeY)
    }

    /** Default Single anchor: centered, fully inside for typical icon cells. */
    val DefaultSingle: Pair<Float, Float> = 0.5f to 0.5f
}
