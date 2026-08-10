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

/**
 * Fail-closed thumbnail decode for corrupt staged files.
 *
 * Progressive host filmstrip uses path-first ImageIO; Export thumbs share the same
 * closed contract via [IosExportThumbnailLoader.decodeFileOrNull] (null, never crash).
 */
@OptIn(ExperimentalForeignApi::class)
class IosFilmstripThumbnailFailureTest {

    @Test
    fun corruptSelectedSource_returnsNullInsteadOfCrashing() {
        val path = NSTemporaryDirectory() +
            "filmstrip_corrupt_${NSUUID().UUIDString()}.jpg"
        assertTrue(
            IosByteArrayInterop.toNSData(byteArrayOf(1, 2, 3, 4, 5))
                .writeToFile(path, atomically = true),
        )

        try {
            assertNull(IosExportThumbnailLoader.decodeFileOrNull(path, maxEdgePx = 96))
        } finally {
            NSFileManager.defaultManager.removeItemAtPath(path, error = null)
        }
    }
}
