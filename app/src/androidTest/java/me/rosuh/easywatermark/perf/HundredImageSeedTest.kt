package me.rosuh.easywatermark.perf

import android.content.Intent
import android.net.Uri
import android.provider.MediaStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Local perf harness only — **not** a CI gate.
 *
 * Seeds the Editor with the `ewm_bench_%` MediaStore fixtures pushed by the host, then holds the
 * process so an adb-driven interaction pass (`input swipe` + `dumpsys gfxinfo`) can measure the
 * multi-image Editor. The system photo picker is not addressable, so entry is production share-in.
 *
 * Opt-in only: it holds the process for minutes, so `connectedDebugAndroidTest` must skip it.
 * Run it deliberately with `-e ewmPerfHold true`, after
 * `pm grant android.permission.READ_MEDIA_IMAGES` and pushing fixtures to
 * `/sdcard/Pictures/EWMBench`.
 */
@LargeTest
@RunWith(AndroidJUnit4::class)
class HundredImageSeedTest {

    @Test
    fun seedEditorWithHundredImages() {
        assumeTrue(
            "opt-in perf harness; pass -e ewmPerfHold true",
            InstrumentationRegistry.getArguments().getString("ewmPerfHold") == "true",
        )
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val uris = queryBenchUris(limit = HOLD_IMAGE_COUNT)
        assertTrue("expected $HOLD_IMAGE_COUNT bench fixtures, got ${uris.size}", uris.size == HOLD_IMAGE_COUNT)

        val intent = Intent(Intent.ACTION_SEND_MULTIPLE).apply {
            setPackage(context.packageName)
            type = "image/jpeg"
            putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris))
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        }
        context.startActivity(intent)

        // Hold the instrumentation so the Editor stays foregrounded for the adb measurement pass.
        Thread.sleep(HOLD_MILLIS)
    }

    private fun queryBenchUris(limit: Int): List<Uri> {
        val resolver = InstrumentationRegistry.getInstrumentation().targetContext.contentResolver
        val out = ArrayList<Uri>(limit)
        resolver.query(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            arrayOf(MediaStore.Images.Media._ID),
            "${MediaStore.Images.Media.DISPLAY_NAME} LIKE ?",
            arrayOf("ewm_bench_%"),
            "${MediaStore.Images.Media.DISPLAY_NAME} ASC",
        )?.use { cursor ->
            val idColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
            while (cursor.moveToNext() && out.size < limit) {
                out += MediaStore.Images.Media.EXTERNAL_CONTENT_URI.buildUpon()
                    .appendPath(cursor.getLong(idColumn).toString())
                    .build()
            }
        }
        return out
    }

    private companion object {
        const val HOLD_IMAGE_COUNT = 100
        const val HOLD_MILLIS = 240_000L
    }
}
