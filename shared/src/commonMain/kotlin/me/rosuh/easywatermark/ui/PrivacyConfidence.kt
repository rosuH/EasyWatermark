package me.rosuh.easywatermark.ui

/**
 * I4 — pure privacy-confidence copy contracts for pick/export decision points.
 *
 * Product UI prefers localized Res strings (`privacy_confidence_pick` /
 * `privacy_confidence_export`). These helpers document the three claims
 * (on-device, selected-only, EXIF strip / ADR-0009) for structural tests.
 */
object PrivacyConfidence {

    /** On-device processing claim (EN default). */
    const val ON_DEVICE_EN: String = "Processing stays on this device."

    /** System-picker / selected-images-only claim (EN default). */
    const val SELECTED_ONLY_EN: String = "Only images you choose are opened."

    /** EXIF strip on export claim (EN default; ADR-0009). */
    const val EXIF_STRIP_EN: String =
        "Exported photos remove location and camera metadata."

    /**
     * Launch / pick surface: on-device + selected-only (not EXIF — user has not exported yet).
     */
    fun pickLine(
        onDevice: String = ON_DEVICE_EN,
        selectedOnly: String = SELECTED_ONLY_EN,
    ): String = joinSuccinct(onDevice, selectedOnly)

    /**
     * Export / save sheet: on-device + EXIF strip (selected set is already fixed).
     */
    fun exportLine(
        onDevice: String = ON_DEVICE_EN,
        exifStrip: String = EXIF_STRIP_EN,
    ): String = joinSuccinct(onDevice, exifStrip)

    /** Product-shipped one-liner keys map to these EN fixtures (dual-write Res). */
    const val PICK_SURFACE_EN: String =
        "On-device only. Opens only the images you select."

    const val EXPORT_SURFACE_EN: String =
        "On-device only. Exported files strip location/camera metadata (EXIF)."

    fun assertsOnDeviceClaim(text: String): Boolean =
        text.contains("on-device", ignoreCase = true) ||
            text.contains("on this device", ignoreCase = true) ||
            text.contains("this device", ignoreCase = true)

    fun assertsSelectedOnlyClaim(text: String): Boolean =
        text.contains("you select", ignoreCase = true) ||
            text.contains("you choose", ignoreCase = true) ||
            text.contains("selected", ignoreCase = true)

    fun assertsExifStripClaim(text: String): Boolean =
        text.contains("EXIF", ignoreCase = true) ||
            (
                text.contains("metadata", ignoreCase = true) &&
                    (
                        text.contains("strip", ignoreCase = true) ||
                            text.contains("remove", ignoreCase = true)
                        )
                )

    private fun joinSuccinct(a: String, b: String): String {
        val left = a.trim().trimEnd('.')
        val right = b.trim()
        return "$left. $right"
    }
}
