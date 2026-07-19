package me.rosuh.easywatermark.utils.bitmap

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import java.io.ByteArrayInputStream
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import me.rosuh.easywatermark.utils.bitmap.AndroidExifTestFixture.BaseHeight
import me.rosuh.easywatermark.utils.bitmap.AndroidExifTestFixture.BaseWidth
import me.rosuh.easywatermark.utils.bitmap.AndroidExifTestFixture.Quadrant
import me.rosuh.easywatermark.utils.bitmap.AndroidExifTestFixture.brightestQuadrant
import me.rosuh.easywatermark.utils.bitmap.AndroidExifTestFixture.jpegWithOrientation

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class BitmapUtilsTest {

    @Test
    fun fullResolutionDecode_usesCallerResolver_andTreatsMissingOrInvalidExifAsNormal() = runBlocking {
        val app: Application = RuntimeEnvironment.getApplication()
        val source = File(app.cacheDir, "b3-plain-source.png").apply {
            outputStream().use { output ->
                Bitmap.createBitmap(9, 7, Bitmap.Config.ARGB_8888).apply {
                    eraseColor(Color.BLUE)
                }.compress(Bitmap.CompressFormat.PNG, 100, output)
            }
        }

        val result = decodeBitmapFromUri(app.contentResolver, Uri.fromFile(source))

        assertTrue(result.isSuccess())
        val decoded = result.data?.bitmap
        assertNotNull(decoded)
        assertEquals(9, decoded!!.width)
        assertEquals(7, decoded.height)

        val invalidExifSource = File(app.cacheDir, "b3-invalid-orientation.jpg").apply {
            writeBytes(jpegWithOrientation(orientation = 9))
        }
        val invalidExifResult = decodeBitmapFromUri(
            app.contentResolver,
            Uri.fromFile(invalidExifSource),
        )
        val invalidExifBitmap = invalidExifResult.data?.bitmap

        assertTrue(invalidExifResult.isSuccess())
        assertNotNull(invalidExifBitmap)
        assertEquals(BaseWidth, invalidExifBitmap!!.width)
        assertEquals(BaseHeight, invalidExifBitmap.height)
        assertEquals(Quadrant.TopLeft, brightestQuadrant(invalidExifBitmap))
    }

    @Test
    @Config(sdk = [23], application = Application::class)
    fun api23_exifInterfaceInputStream_readsOrientationTags1Through8() {
        assertExifFixtureTagsReadable()
    }

    @Test
    @Config(sdk = [24], application = Application::class)
    fun api24_exifInterfaceInputStream_readsOrientationTags1Through8() {
        assertExifFixtureTagsReadable()
    }

    @Test
    @Config(sdk = [28], application = Application::class)
    fun api28_fullResolutionDecode_bakesExifOrientations1Through8() = runBlocking {
        assertFullResolutionOrientations(1..8)
    }

    @Test
    @Config(sdk = [29], application = Application::class)
    fun api29_fullResolutionDecode_bakesExifOrientations1Through8() = runBlocking {
        assertFullResolutionOrientations(1..8)
    }

    @Test
    @Config(sdk = [34], application = Application::class)
    fun api34_fullResolutionDecode_bakesExifOrientations1Through8() = runBlocking {
        assertFullResolutionOrientations(1..8)
    }

    @Test
    fun sampledDecode_swapsBoundsOnlyForOrientations5Through8() {
        val app: Application = RuntimeEnvironment.getApplication()
        val expectedUnswappedQuadrants = mapOf(
            1 to Quadrant.TopLeft,
            2 to Quadrant.TopRight,
            3 to Quadrant.BottomRight,
            4 to Quadrant.BottomLeft,
        )
        expectedUnswappedQuadrants.forEach { (orientation, expectedQuadrant) ->
            val source = File(app.cacheDir, "b3-sampled-orientation-$orientation.jpg").apply {
                writeBytes(jpegWithOrientation(orientation, width = 400, height = 100))
            }
            val notSwapped = decodeSampledBitmapFromResourceSync(
                resolver = app.contentResolver,
                uri = Uri.fromFile(source),
                reqWidth = 20,
                reqHeight = 80,
            ).data!!

            assertEquals(
                "orientation $orientation must not swap sampling bounds",
                2,
                notSwapped.inSample,
            )
            assertEquals("orientation $orientation width", 200, notSwapped.bitmap.width)
            assertEquals("orientation $orientation height", 50, notSwapped.bitmap.height)
            assertEquals(
                "orientation $orientation sampled quadrant",
                expectedQuadrant,
                brightestQuadrant(notSwapped.bitmap),
            )
        }
        val expectedQuadrants = mapOf(
            5 to Quadrant.TopLeft,
            6 to Quadrant.TopRight,
            7 to Quadrant.BottomRight,
            8 to Quadrant.BottomLeft,
        )
        expectedQuadrants.forEach { (orientation, expectedQuadrant) ->
            val source = File(app.cacheDir, "b3-sampled-orientation-$orientation.jpg").apply {
                writeBytes(
                    jpegWithOrientation(
                        orientation = orientation,
                        width = 400,
                        height = 100,
                    ),
                )
            }
            val swapped = decodeSampledBitmapFromResourceSync(
                resolver = app.contentResolver,
                uri = Uri.fromFile(source),
                reqWidth = 20,
                reqHeight = 80,
            ).data!!

            assertEquals("orientation $orientation must swap sampling bounds", 4, swapped.inSample)
            assertEquals("orientation $orientation width", 25, swapped.bitmap.width)
            assertEquals("orientation $orientation height", 100, swapped.bitmap.height)
            assertEquals(
                "orientation $orientation sampled quadrant",
                expectedQuadrant,
                brightestQuadrant(swapped.bitmap),
            )
        }
    }

    private fun assertExifFixtureTagsReadable() {
        for (orientation in 1..8) {
            val parsedOrientation = ExifInterface(
                ByteArrayInputStream(jpegWithOrientation(orientation)),
            ).getAttributeInt(
                ExifInterface.TAG_ORIENTATION,
                ExifInterface.ORIENTATION_UNDEFINED,
            )
            assertEquals("orientation $orientation fixture tag", orientation, parsedOrientation)
        }
    }

    private suspend fun assertFullResolutionOrientations(orientations: Iterable<Int>) {
        val expected = mapOf(
            1 to Triple(BaseWidth, BaseHeight, Quadrant.TopLeft),
            2 to Triple(BaseWidth, BaseHeight, Quadrant.TopRight),
            3 to Triple(BaseWidth, BaseHeight, Quadrant.BottomRight),
            4 to Triple(BaseWidth, BaseHeight, Quadrant.BottomLeft),
            5 to Triple(BaseHeight, BaseWidth, Quadrant.TopLeft),
            6 to Triple(BaseHeight, BaseWidth, Quadrant.TopRight),
            7 to Triple(BaseHeight, BaseWidth, Quadrant.BottomRight),
            8 to Triple(BaseHeight, BaseWidth, Quadrant.BottomLeft),
        )
        val app: Application = RuntimeEnvironment.getApplication()

        orientations.forEach { orientation ->
            val contract = checkNotNull(expected[orientation])
            val fixture = jpegWithOrientation(orientation)
            val source = File(app.cacheDir, "b3-orientation-$orientation.jpg").apply {
                writeBytes(fixture)
            }

            val result = decodeBitmapFromUri(app.contentResolver, Uri.fromFile(source))
            val decoded = result.data?.bitmap

            assertTrue("orientation $orientation must decode", result.isSuccess())
            assertNotNull("orientation $orientation must return pixels", decoded)
            assertEquals("orientation $orientation width", contract.first, decoded!!.width)
            assertEquals("orientation $orientation height", contract.second, decoded.height)
            assertEquals(
                "orientation $orientation bright quadrant",
                contract.third,
                brightestQuadrant(decoded),
            )
        }
    }

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
