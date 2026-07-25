package me.rosuh.macrobenchmark.journey

import android.content.ContentValues
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.benchmark.macro.MacrobenchmarkScope
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Until
import java.io.ByteArrayOutputStream

/**
 * H1 shared product journeys for Baseline Profile generation and Macrobenchmark measure.
 *
 * Automation constraints:
 * - System PHPicker is not reliably addressable → Editor is entered via **share-in**
 *   ([Intent.ACTION_SEND] + MediaStore image URI), matching production
 *   [me.rosuh.easywatermark.ui.ComposeMainActivity] share bridge.
 * - Does not invent latency SLOs; helpers only drive UI / intents.
 */
object ProductJourneys {

    private const val DEFAULT_TIMEOUT_MS = 10_000L

    /** Cold / warm start to Launch surface. */
    fun MacrobenchmarkScope.startLaunchAndWait() {
        startActivityAndWait()
        device.waitForIdle()
        // Launch primary CTA is localized English default "Choose Images".
        device.wait(Until.hasObject(By.textContains("Choose Images")), DEFAULT_TIMEOUT_MS)
    }

    /**
     * Open About from the **current** Launch screen (no gallery required).
     * Caller must already be on Launch ([startLaunchAndWait]).
     * About control uses contentDescription from product strings.
     */
    fun MacrobenchmarkScope.openAboutFromLaunch() {
        val about = device.wait(Until.findObject(By.descContains("About")), DEFAULT_TIMEOUT_MS)
            ?: device.findObject(By.descContains("about"))
        about?.click()
        device.waitForIdle()
        // About title surfaces product strings ("About" / "Information").
        device.wait(
            Until.hasObject(By.textContains("About")),
            DEFAULT_TIMEOUT_MS,
        )
    }

    /**
     * Enter Editor by sharing a synthetic PNG via MediaStore (production share-in path).
     * Returns false when MediaStore insert fails (caller may residual).
     */
    fun MacrobenchmarkScope.tryEnterEditorViaShare(): Boolean {
        val uri = insertFixtureImage() ?: return false
        val intent = Intent(Intent.ACTION_SEND).apply {
            setPackage(packageName)
            type = "image/png"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        }
        startActivityAndWait(intent)
        device.waitForIdle()
        // Editor save action contentDescription typically includes "Save" / product save label.
        val editor = device.wait(Until.hasObject(By.descContains("Save")), DEFAULT_TIMEOUT_MS)
            ?: device.wait(Until.hasObject(By.descContains("save")), DEFAULT_TIMEOUT_MS)
        return editor != null
    }

    /**
     * Open export / save sheet from Editor if the save control is present.
     */
    fun MacrobenchmarkScope.tryOpenExportEntry(): Boolean {
        val save = device.wait(Until.findObject(By.descContains("Save")), DEFAULT_TIMEOUT_MS)
            ?: device.findObject(By.descContains("save"))
            ?: return false
        save.click()
        device.waitForIdle()
        // Best-effort: sheet may animate; frame metrics still capture the interaction.
        return true
    }

    /**
     * Best-effort filmstrip / multi-select is not available without multi share;
     * multi-share two fixtures when possible.
     */
    fun MacrobenchmarkScope.tryEnterEditorMultiShare(): Boolean {
        val uri1 = insertFixtureImage(displayName = "ewm_bench_a.png") ?: return false
        val uri2 = insertFixtureImage(displayName = "ewm_bench_b.png") ?: return false
        val intent = Intent(Intent.ACTION_SEND_MULTIPLE).apply {
            setPackage(packageName)
            type = "image/png"
            putParcelableArrayListExtra(Intent.EXTRA_STREAM, arrayListOf(uri1, uri2))
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        }
        startActivityAndWait(intent)
        device.waitForIdle()
        return device.wait(Until.hasObject(By.descContains("Save")), DEFAULT_TIMEOUT_MS) != null
    }

    /**
     * Representative Launch control: click pick button (does not complete PHPicker grid).
     * Caller should already be on Launch.
     */
    fun MacrobenchmarkScope.focusPickControl() {
        device.findObject(By.textContains("Choose Images"))?.click()
        // Picker may open; press back to return if system UI appears.
        device.waitForIdle(1_000)
        device.pressBack()
        device.waitForIdle()
    }

    private fun insertFixtureImage(
        displayName: String = "ewm_bench_fixture.png",
    ): android.net.Uri? {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val resolver = context.contentResolver
        val png = solidPngBytes(width = 64, height = 48)
        return try {
            if (Build.VERSION.SDK_INT >= 29) {
                val values = ContentValues().apply {
                    put(MediaStore.Images.Media.DISPLAY_NAME, displayName)
                    put(MediaStore.Images.Media.MIME_TYPE, "image/png")
                    put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/EasyWatermarkBench")
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
        } catch (_: Throwable) {
            null
        }
    }

    private fun solidPngBytes(width: Int, height: Int): ByteArray {
        val bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        Canvas(bmp).drawColor(Color.rgb(0x20, 0x30, 0x40))
        val out = ByteArrayOutputStream()
        bmp.compress(Bitmap.CompressFormat.PNG, 100, out)
        bmp.recycle()
        return out.toByteArray()
    }
}
