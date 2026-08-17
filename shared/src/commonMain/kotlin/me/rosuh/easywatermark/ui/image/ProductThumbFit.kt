package me.rosuh.easywatermark.ui.image

/**
 * Filmstrip / gallery cells crop a square. System `loadThumbnail(Size(n, n))`
 * and Coil's default `Scale.FIT` instead **fit the whole image into that square**,
 * so a 1080×8000 长图 becomes ~17×128 and Crop upscales the sliver.
 */
object ProductThumbFit {
    /**
     * Keep a system square thumb when its short edge is still useful for Crop.
     * 4:3 camera Fit (~128×96 at request 128) stays; 16:9 / 长图 / 宽图 fall through.
     */
    fun isUsableSquareThumb(widthPx: Int, heightPx: Int, requestedEdgePx: Int): Boolean {
        val short = minOf(widthPx, heightPx)
        if (short <= 0 || requestedEdgePx <= 0) return false
        return short >= (requestedEdgePx * 3) / 4
    }

    /**
     * Power-of-two `inSampleSize` that keeps the decoded short edge ≥ [minShortEdgePx].
     */
    fun inSampleSizeForCrop(outWidth: Int, outHeight: Int, minShortEdgePx: Int): Int {
        var inSampleSize = 1
        val shortest = minOf(outWidth, outHeight)
        if (shortest > minShortEdgePx && minShortEdgePx > 0) {
            val half = shortest / 2
            while (half / inSampleSize >= minShortEdgePx) {
                inSampleSize *= 2
            }
        }
        return inSampleSize.coerceAtLeast(1)
    }
}
