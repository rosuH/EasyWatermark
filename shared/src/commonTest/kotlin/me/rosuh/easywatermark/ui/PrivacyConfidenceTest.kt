package me.rosuh.easywatermark.ui

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * I4 — privacy confidence copy contracts (pick + export; ADR-0009 EXIF strip).
 * Device legal review residual; structural only here.
 */
class PrivacyConfidenceTest {

    @Test
    fun pickSurface_onDeviceAndSelectedOnly_notExifEssay() {
        val pick = PrivacyConfidence.PICK_SURFACE_EN
        assertTrue(PrivacyConfidence.assertsOnDeviceClaim(pick), "pick must claim on-device: $pick")
        assertTrue(PrivacyConfidence.assertsSelectedOnlyClaim(pick), "pick must claim selected-only: $pick")
        // EXIF is export-time; pick line need not mention it
        assertTrue(pick.length < 120, "pick line must stay succinct")
    }

    @Test
    fun exportSurface_onDeviceAndExifStrip() {
        val export = PrivacyConfidence.EXPORT_SURFACE_EN
        assertTrue(PrivacyConfidence.assertsOnDeviceClaim(export), "export must claim on-device: $export")
        assertTrue(PrivacyConfidence.assertsExifStripClaim(export), "export must claim EXIF strip: $export")
        assertTrue(export.length < 140, "export line must stay succinct")
    }

    @Test
    fun composedPickAndExport_fromFragments() {
        val pick = PrivacyConfidence.pickLine()
        assertTrue(PrivacyConfidence.assertsOnDeviceClaim(pick))
        assertTrue(PrivacyConfidence.assertsSelectedOnlyClaim(pick))

        val export = PrivacyConfidence.exportLine()
        assertTrue(PrivacyConfidence.assertsOnDeviceClaim(export))
        assertTrue(PrivacyConfidence.assertsExifStripClaim(export))
    }

    @Test
    fun atomClaims_areNonEmpty() {
        assertTrue(PrivacyConfidence.ON_DEVICE_EN.isNotBlank())
        assertTrue(PrivacyConfidence.SELECTED_ONLY_EN.isNotBlank())
        assertTrue(PrivacyConfidence.EXIF_STRIP_EN.isNotBlank())
        assertTrue(PrivacyConfidence.assertsExifStripClaim(PrivacyConfidence.EXIF_STRIP_EN))
    }

    @Test
    fun emptyText_failsClaims() {
        assertFalse(PrivacyConfidence.assertsOnDeviceClaim(""))
        assertFalse(PrivacyConfidence.assertsSelectedOnlyClaim("hello"))
        assertFalse(PrivacyConfidence.assertsExifStripClaim("on-device only"))
    }
}
