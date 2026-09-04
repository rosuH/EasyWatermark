package me.rosuh.easywatermark.ui.compose

/**
 * Shared watermark text-field contract for form inspector + phone edit sheet.
 *
 * Large-screen path must not silently force single-line while the raster paints `\n`
 * (research 2026-08-10 / hard door H1+H8).
 */
object WatermarkTextFieldDefaults {
    /** Form + sheet both allow newlines; never force singleLine on product watermark text. */
    const val singleLine: Boolean = false

    /** Visible lines in the form inspector (H1: ≥2–3). */
    const val formMinLines: Int = 3

    const val formMaxLines: Int = 8

    /** Phone sheet grows with content; soft cap matches form. */
    const val sheetMinLines: Int = 3

    const val sheetMaxLines: Int = 8

    /** Phone bottom summary may show more than one line when text contains newlines. */
    const val summaryMaxLines: Int = 2
}
