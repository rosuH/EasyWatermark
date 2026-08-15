package me.rosuh.easywatermark.render

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class IosPhotoKitImageSourceTest {

    @Test
    fun resolveAsset_unknownId_isMiss() {
        assertNull(IosPhotoKitImageSource.resolveAsset(""))
        assertNull(IosPhotoKitImageSource.resolveAsset("not-a-real-local-identifier"))
    }

    @Test
    fun request_zeroDeadline_returnsTimeoutNull_withoutThrow() = runBlocking {
        val timing = IosPhotoKitImageSource.awaitImageRequest(deadlineMs = 0) { _, _, _ ->
            error("start must not run when deadline is 0")
        }
        assertTrue(timing.timedOut)
        assertNull(timing.frame)
    }

    @Test
    fun request_neverCompletes_timesOutNull_withoutThrow() = runBlocking {
        val timing = IosPhotoKitImageSource.awaitImageRequest(deadlineMs = 30) { _, _, onCancelable ->
            onCancelable(1) { }
        }
        assertTrue(timing.timedOut)
        assertNull(timing.frame)
        assertFalse(timing.missed)
    }
}
