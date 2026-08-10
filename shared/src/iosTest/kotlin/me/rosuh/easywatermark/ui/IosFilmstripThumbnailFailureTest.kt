package me.rosuh.easywatermark.ui

import kotlin.test.Test
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.cinterop.ExperimentalForeignApi
import me.rosuh.easywatermark.render.IosByteArrayInterop
import platform.Foundation.NSFileManager
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSUUID
import platform.Foundation.writeToFile

@OptIn(ExperimentalForeignApi::class)
class IosFilmstripThumbnailFailureTest {

    @Test
    fun corruptSelectedSource_returnsPlaceholderInsteadOfCrashing() {
        val path = NSTemporaryDirectory() +
            "filmstrip_corrupt_${NSUUID().UUIDString()}.jpg"
        assertTrue(
            IosByteArrayInterop.toNSData(byteArrayOf(1, 2, 3, 4, 5))
                .writeToFile(path, atomically = true),
        )

        try {
            assertNull(decodeIosFilmstripThumbOrNull(path))
        } finally {
            NSFileManager.defaultManager.removeItemAtPath(path, error = null)
        }
    }
}
