package me.rosuh.easywatermark.ui

/**
 * I2 — pure helpers for production-seam accessibility labels (name / value / selected).
 * Hosts and composables assemble localized fragments; this keeps unit-testable contracts.
 */
object AccessibilitySemantics {

    /**
     * Slider name + current value for screen readers.
     * When [label] is blank, value alone is the accessible name.
     */
    fun sliderContentDescription(label: String?, valueDisplay: String): String {
        val v = valueDisplay.trim()
        val name = label?.trim().orEmpty()
        return if (name.isEmpty()) v else "$name, $v"
    }

    /**
     * Gallery card: selected/unselected state + image name.
     * [selectedPhrase] / [unselectedPhrase] are localized short states (e.g. "Selected").
     */
    fun galleryImageContentDescription(
        imageName: String,
        selected: Boolean,
        selectedPhrase: String,
        unselectedPhrase: String,
    ): String {
        val name = imageName.trim().ifEmpty { "image" }
        val state = if (selected) selectedPhrase else unselectedPhrase
        return "$state, $name"
    }

    /** Checkbox decoration CD — prefer localized [checkboxLabel] over hard-coded English. */
    fun checkboxContentDescription(checkboxLabel: String, selected: Boolean): String {
        val base = checkboxLabel.trim().ifEmpty { "Checkbox" }
        return if (selected) "$base, selected" else base
    }
}
