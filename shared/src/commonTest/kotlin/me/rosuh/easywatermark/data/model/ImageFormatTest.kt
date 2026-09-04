package me.rosuh.easywatermark.data.model

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Pins `ImageFormat.fileExtension`: the single source for the default output-file extension
 * That Android (`MainViewModel.trapOutputExtension`) and Desktop (`DesktopSaveDecision.defaultOutputFileName`) * now share. The strings must stay `"jpg"`/`"png"` (byte-identical to the old inline mappings; the Android
 * `image/<ext>` MIME shape depends on these). `fromStorageId` round-trip is re-pinned here too.
 */
class ImageFormatTest {

    @Test
    fun fileExtension_is_the_bare_jpg_png_strings() {
        assertEquals("jpg", ImageFormat.JPEG.fileExtension)
        assertEquals("png", ImageFormat.PNG.fileExtension)
    }

    @Test
    fun fromStorageId_round_trips_and_defaults_to_JPEG() {
        assertEquals(ImageFormat.JPEG, ImageFormat.fromStorageId(0))
        assertEquals(ImageFormat.PNG, ImageFormat.fromStorageId(1))
        assertEquals(ImageFormat.JPEG, ImageFormat.fromStorageId(null))
        assertEquals(ImageFormat.JPEG, ImageFormat.fromStorageId(99))
    }
}
