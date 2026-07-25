package me.rosuh.easywatermark.ui

import me.rosuh.easywatermark.ui.save.ExportRecoveryUi
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * I2 — pure accessibility label contracts for production seams.
 * Device TalkBack/VoiceOver matrix is residual (see evidence/i2).
 */
class AccessibilitySemanticsTest {

    @Test
    fun slider_namePlusValue() {
        assertEquals("Opacity, 42", AccessibilitySemantics.sliderContentDescription("Opacity", "42"))
        assertEquals("80", AccessibilitySemantics.sliderContentDescription(null, "80"))
        assertEquals("80", AccessibilitySemantics.sliderContentDescription("  ", "80"))
        assertEquals("Quality, 60", AccessibilitySemantics.sliderContentDescription("Quality", "60"))
    }

    @Test
    fun gallery_selectedStateInName() {
        val selected = AccessibilitySemantics.galleryImageContentDescription(
            imageName = "photo.jpg",
            selected = true,
            selectedPhrase = "Selected",
            unselectedPhrase = "Not selected",
        )
        assertEquals("Selected, photo.jpg", selected)
        val unselected = AccessibilitySemantics.galleryImageContentDescription(
            imageName = "photo.jpg",
            selected = false,
            selectedPhrase = "Selected",
            unselectedPhrase = "Not selected",
        )
        assertEquals("Not selected, photo.jpg", unselected)
        val emptyName = AccessibilitySemantics.galleryImageContentDescription(
            imageName = "  ",
            selected = false,
            selectedPhrase = "Selected",
            unselectedPhrase = "Not selected",
        )
        assertTrue(emptyName.contains("image"))
    }

    @Test
    fun checkbox_notHardCodedEnglishOnly() {
        val cd = AccessibilitySemantics.checkboxContentDescription("Checkbox", selected = true)
        assertTrue(cd.contains("Checkbox"))
        assertTrue(cd.contains("selected"))
        assertFalse(cd.equals("check box", ignoreCase = true))
    }

    @Test
    fun export_progressCd_notColorOnly_exposesCounts() {
        val exporting = ExportRecoveryUi.fromJob(
            isSaving = true,
            isFinished = false,
            successCount = 1,
            failureCount = 0,
            processedCount = 2,
            totalCount = 5,
        )
        val cd = ExportRecoveryUi.contentDescription(exporting)
        assertTrue(cd.contains("progress", ignoreCase = true) || cd.contains("Export"))
        assertTrue(cd.contains("2") && cd.contains("5"))
        assertTrue(cd.contains("1")) // succeeded
        // Not color-only: textual processed/succeeded/failed present
        assertTrue(
            cd.contains("processed", ignoreCase = true) &&
                cd.contains("succeeded", ignoreCase = true) &&
                cd.contains("failed", ignoreCase = true),
        )
    }

    @Test
    fun export_finishedCd_exposesCounts() {
        val done = ExportRecoveryUi.fromJob(
            isSaving = false,
            isFinished = true,
            successCount = 3,
            failureCount = 1,
            processedCount = 4,
            totalCount = 4,
        )
        val cd = ExportRecoveryUi.contentDescription(done)
        assertTrue(cd.contains("finished", ignoreCase = true) || cd.contains("Export"))
        assertTrue(cd.contains("3") && cd.contains("1") && cd.contains("4"))
    }
}
