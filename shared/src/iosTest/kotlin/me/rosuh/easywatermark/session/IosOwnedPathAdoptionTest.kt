@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package me.rosuh.easywatermark.session

import me.rosuh.easywatermark.render.IosByteArrayInterop
import platform.Foundation.NSFileManager
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSUUID
import platform.Foundation.writeToFile
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class IosOwnedPathAdoptionTest {

    @Test
    fun adoptOwnedFile_copiesProviderOwnedPath_toStableSessionSource_andDoesNotRequireByteArrayBridge() {
        val provisional = NSTemporaryDirectory() + "ewm_import_provisional_" + NSUUID().UUIDString()
        val payload = byteArrayOf(7, 1, 9, 3, 2)
        assertTrue(IosByteArrayInterop.toNSData(payload).writeToFile(provisional, atomically = true))
        try {
            val adopted = IosSourceStager.adoptOwnedFile(provisional)
            assertTrue(adopted.contains("ewm_src_"))
            assertTrue(NSFileManager.defaultManager.fileExistsAtPath(adopted))
            assertContentEquals(payload, IosSourceStager.readBytesForTests(adopted))
            assertTrue(NSFileManager.defaultManager.fileExistsAtPath(provisional), "adoption copies; caller owns provisional cleanup")
            IosSourceStager.deleteQuietly(adopted)
        } finally {
            IosSourceStager.deleteQuietly(provisional)
        }
        assertFalse(NSFileManager.defaultManager.fileExistsAtPath(provisional))
    }

    @Test
    fun adoptOwnedFile_refusesForeignPath_beforeAnyDeletion() {
        val foreign = NSTemporaryDirectory() + "not_ours_" + NSUUID().UUIDString()
        assertTrue(IosByteArrayInterop.toNSData(byteArrayOf(1)).writeToFile(foreign, atomically = true))
        try {
            kotlin.test.assertFailsWith<IllegalArgumentException> {
                IosSourceStager.adoptOwnedFile(foreign)
            }
            assertTrue(NSFileManager.defaultManager.fileExistsAtPath(foreign))
        } finally {
            IosSourceStager.deleteQuietly(foreign)
        }
    }
}
