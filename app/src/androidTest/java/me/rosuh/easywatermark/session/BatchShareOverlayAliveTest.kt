package me.rosuh.easywatermark.session

import android.content.ContentValues
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.SystemClock
import android.provider.MediaStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.ByteArrayOutputStream
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Same scene as the iOS 48-photo pick that hit overlay [composeTextCell]: production
 * [Intent.ACTION_SEND_MULTIPLE] into the editor, then hold long enough for the first
 * live overlay + neighbor decode. Not a CI gate by duration — local device/emulator only.
 */
@RunWith(AndroidJUnit4::class)
class BatchShareOverlayAliveTest {

    @Test
    fun shareFortyEight_editorActivityStaysAlive() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val uris = (0 until BATCH).map { i ->
            insertPng(context.contentResolver, "ewm_batch_${i.toString().padStart(2, '0')}.png", i)
                ?: error("MediaStore insert failed at $i")
        }
        try {
            val intent = Intent(Intent.ACTION_SEND_MULTIPLE).apply {
                setClassName(context.packageName, "me.rosuh.easywatermark.ui.MainActivity")
                type = "image/png"
                putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris))
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            }
            val activity = instrumentation.startActivitySync(intent)
            assertNotNull(activity)
            val holdMs = InstrumentationRegistry.getArguments()
                .getString("ewmHoldMs")
                ?.toLongOrNull()
                ?: HOLD_MS
            val deadline = SystemClock.uptimeMillis() + holdMs
            while (SystemClock.uptimeMillis() < deadline) {
                assertFalse("editor finishing — likely crash", activity.isFinishing)
                assertFalse("editor destroyed — likely crash", activity.isDestroyed)
                Thread.sleep(400)
            }
            assertFalse(activity.isFinishing)
            assertFalse(activity.isDestroyed)
            assertTrue(uris.size == BATCH)
        } finally {
            uris.forEach { context.contentResolver.delete(it, null, null) }
        }
    }

    private fun insertPng(
        resolver: android.content.ContentResolver,
        displayName: String,
        seed: Int,
    ): Uri? {
        val png = solidPngBytes(seed)
        return try {
            if (Build.VERSION.SDK_INT >= 29) {
                val values = ContentValues().apply {
                    put(MediaStore.Images.Media.DISPLAY_NAME, displayName)
                    put(MediaStore.Images.Media.MIME_TYPE, "image/png")
                    put(
                        MediaStore.Images.Media.RELATIVE_PATH,
                        Environment.DIRECTORY_PICTURES + "/EWMBatch",
                    )
                    put(MediaStore.Images.Media.IS_PENDING, 1)
                }
                val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                    ?: return null
                resolver.openOutputStream(uri)?.use { it.write(png) } ?: return null
                values.clear()
                values.put(MediaStore.Images.Media.IS_PENDING, 0)
                resolver.update(uri, values, null, null)
                uri
            } else {
                val values = ContentValues().apply {
                    put(MediaStore.Images.Media.DISPLAY_NAME, displayName)
                    put(MediaStore.Images.Media.MIME_TYPE, "image/png")
                }
                val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                    ?: return null
                resolver.openOutputStream(uri)?.use { it.write(png) } ?: return null
                uri
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun solidPngBytes(seed: Int): ByteArray {
        val bmp = Bitmap.createBitmap(1280, 720, Bitmap.Config.ARGB_8888)
        Canvas(bmp).drawColor(
            Color.rgb(40 + (seed * 17) % 200, 60 + (seed * 31) % 180, 80 + (seed * 13) % 160),
        )
        return ByteArrayOutputStream().use { out ->
            bmp.compress(Bitmap.CompressFormat.PNG, 100, out)
            bmp.recycle()
            out.toByteArray()
        }
    }

    private companion object {
        const val BATCH = 48
        const val HOLD_MS = 20_000L
    }
}
