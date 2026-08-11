package me.rosuh.cmonet

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Structural proof for ADR-0027 wallpaper gate: availability is system ∧ follow-wallpaper.
 * No OEM manufacturer set is consulted.
 */
class MonetManufacturerTest {
    @Test
    fun available_whenSystemYesAndFollowYes() {
        assertTrue(
            MonetManufacturer.resolveWallpaperDynamicAvailable(
                systemDynamicAvailable = true,
                followWallpaper = true,
            ),
        )
    }

    @Test
    fun unavailable_whenFollowOffEvenIfSystemYes() {
        assertFalse(
            MonetManufacturer.resolveWallpaperDynamicAvailable(
                systemDynamicAvailable = true,
                followWallpaper = false,
            ),
        )
    }

    @Test
    fun unavailable_whenSystemNoEvenIfFollowYes() {
        assertFalse(
            MonetManufacturer.resolveWallpaperDynamicAvailable(
                systemDynamicAvailable = false,
                followWallpaper = true,
            ),
        )
    }
}
