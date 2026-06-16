package me.rosuh.easywatermark.utils.bitmap

import android.app.Application
import android.net.Uri
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class BitmapUtilsTest {

    @Test
    fun sampledDecodeFailureReturnsFailureInsteadOfCachingNull() = runBlocking {
        val resolver = RuntimeEnvironment.getApplication().contentResolver
        val result = decodeSampledBitmapFromResource(
            resolver,
            Uri.parse("content://me.rosuh.easywatermark.test/missing-image.jpg"),
            reqWidth = 320,
            reqHeight = 240,
        )

        assertTrue(result.isFailure())
    }
}
