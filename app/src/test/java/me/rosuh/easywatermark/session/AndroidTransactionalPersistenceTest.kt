package me.rosuh.easywatermark.session

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import kotlinx.coroutines.runBlocking
import me.rosuh.easywatermark.data.model.ImageFormat
import me.rosuh.easywatermark.data.model.ImageInfo
import me.rosuh.easywatermark.data.model.MediaRef
import me.rosuh.easywatermark.data.model.UserPreferences
import me.rosuh.easywatermark.data.model.WaterMark
import me.rosuh.easywatermark.data.model.WatermarkMode
import me.rosuh.easywatermark.utils.FileUtils.Companion.outPutFolderName
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * D3 A1–A5: Android transactional export persistence (pending-row cleanup + pre-Q temp/rename).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AndroidTransactionalPersistenceTest {

    private val app: Application = RuntimeEnvironment.getApplication()

    private fun solidPngFile(name: String, w: Int = 64, h: Int = 48): File {
        val f = File(app.cacheDir, name)
        f.parentFile?.mkdirs()
        f.outputStream().use { out ->
            val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            bmp.eraseColor(Color.rgb(20, 40, 80))
            bmp.compress(Bitmap.CompressFormat.PNG, 100, out)
            bmp.recycle()
        }
        return f
    }

    private fun imageInfo(file: File): ImageInfo = ImageInfo(
        uri = MediaRef(Uri.fromFile(file).toString()),
        width = 64,
        height = 48,
    )

    private fun defaultConfig(): WaterMark = WaterMark.default.copy(
        text = "D3",
        textSize = 18f,
        markMode = WatermarkMode.Text,
    )

    private fun pngPrefs() = UserPreferences(ImageFormat.PNG, 90)

    private fun pendingRowCount(): Int {
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.Q) return 0
        val collection = MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        app.contentResolver.query(
            collection,
            arrayOf(MediaStore.Images.Media._ID, MediaStore.Images.Media.IS_PENDING),
            "${MediaStore.Images.Media.IS_PENDING}=?",
            arrayOf("1"),
            null,
        ).use { cursor ->
            return cursor?.count ?: 0
        }
    }

    private fun mediaRowExists(uri: Uri): Boolean {
        return try {
            app.contentResolver.query(
                uri,
                arrayOf(MediaStore.Images.Media._ID),
                null,
                null,
                null,
            ).use { it != null && it.moveToFirst() }
        } catch (_: Exception) {
            false
        }
    }

    private fun isPending(uri: Uri): Int? {
        return app.contentResolver.query(
            uri,
            arrayOf(MediaStore.Images.Media.IS_PENDING),
            null,
            null,
            null,
        ).use { cursor ->
            if (cursor == null || !cursor.moveToFirst()) return@use null
            cursor.getInt(0)
        }
    }

    /** A1 — Q+ compress=false after insert → Encode failure + no pending leftover. */
    @Test
    @Config(sdk = [34], application = Application::class)
    fun a1_qPlus_compressFalse_deletesPendingRow() = runBlocking {
        val beforePending = pendingRowCount()
        val src = solidPngFile("d3-a1-src.png")
        val port = AndroidExportPipelinePort(
            appContext = app,
            hooks = AndroidExportPipelinePort.PersistenceHooks(
                compress = { _, _, _, _ -> false },
            ),
        )
        val result = port.exportOne(imageInfo(src), defaultConfig(), pngPrefs())
        assertTrue(result.isFailure())
        val failure = (result as ExportOutcome.Failure).failure
        assertTrue(failure is ExportFailure.Encode)
        assertEquals(beforePending, pendingRowCount())
    }

    /** A2 — Q+ openFileDescriptor null after insert → Persistence failure + cleanup. */
    @Test
    @Config(sdk = [34], application = Application::class)
    fun a2_qPlus_openDescriptorNull_deletesPendingRow() = runBlocking {
        val beforePending = pendingRowCount()
        val src = solidPngFile("d3-a2-src.png")
        val port = AndroidExportPipelinePort(
            appContext = app,
            hooks = AndroidExportPipelinePort.PersistenceHooks(
                openWriteDescriptor = { _, _ -> null },
            ),
        )
        val result = port.exportOne(imageInfo(src), defaultConfig(), pngPrefs())
        assertTrue(result.isFailure())
        val failure = (result as ExportOutcome.Failure).failure
        assertTrue(failure is ExportFailure.Persistence)
        assertEquals(beforePending, pendingRowCount())
    }

    /** A3 — Q+ happy path → success + IS_PENDING cleared + readable bytes. */
    @Test
    @Config(sdk = [34], application = Application::class)
    fun a3_qPlus_success_isPendingCleared_bytesReadable() = runBlocking {
        val src = solidPngFile("d3-a3-src.png")
        val port = AndroidExportPipelinePort(appContext = app)
        val result = port.exportOne(imageInfo(src), defaultConfig(), pngPrefs())
        assertTrue(result.isSuccess())
        val media = (result as ExportOutcome.Success).media
        val uri = Uri.parse(media.ref.value)
        assertTrue("success URI must exist", mediaRowExists(uri))
        assertEquals("IS_PENDING must be 0 after publish", 0, isPending(uri))
        assertTrue(media.byteCount > 0)
        app.contentResolver.openInputStream(uri).use { stream ->
            assertNotNull(stream)
            val bytes = stream!!.readBytes()
            assertTrue(bytes.isNotEmpty())
            assertTrue(
                bytes.take(8).toByteArray().contentEquals(
                    byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A),
                ),
            )
        }
        assertEquals(0, pendingRowCount())
    }

    /** A4 — Pre-Q encode failure → no final file under Pictures folder. */
    @Test
    @Config(sdk = [28], application = Application::class)
    fun a4_preQ_encodeFailure_noFinalFile() = runBlocking {
        val pictures = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES)
        val mediaDir = File(pictures, outPutFolderName)
        mediaDir.mkdirs()
        val before = mediaDir.listFiles()?.filter { it.isFile && it.name.startsWith("ewm_") }?.toSet()
            ?: emptySet()

        val src = solidPngFile("d3-a4-src.png")
        val port = AndroidExportPipelinePort(
            appContext = app,
            hooks = AndroidExportPipelinePort.PersistenceHooks(
                compress = { _, _, _, _ -> false },
            ),
        )
        val result = port.exportOne(imageInfo(src), defaultConfig(), pngPrefs())
        assertTrue(result.isFailure())
        assertTrue((result as ExportOutcome.Failure).failure is ExportFailure.Encode)

        val after = mediaDir.listFiles()?.filter { it.isFile }?.toList() ?: emptyList()
        val newFinals = after.filter { it.name.startsWith("ewm_") && !it.name.contains(".ewm_tmp") }
            .filter { it !in before }
        assertTrue(
            "pre-Q failure must not leave new final ewm_ files (got $newFinals)",
            newFinals.isEmpty(),
        )
        val temps = after.filter { it.name.contains(".ewm_tmp") }
        assertTrue("temp must be cleaned (got $temps)", temps.isEmpty())
    }

    /** A5 — Pre-Q success → final file exists with bytes. */
    @Test
    @Config(sdk = [28], application = Application::class)
    fun a5_preQ_success_finalFileExists() = runBlocking {
        val pictures = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES)
        val mediaDir = File(pictures, outPutFolderName)
        mediaDir.mkdirs()

        val src = solidPngFile("d3-a5-src.png")
        val port = AndroidExportPipelinePort(appContext = app)
        val result = port.exportOne(imageInfo(src), defaultConfig(), pngPrefs())
        assertTrue(
            "pre-Q success expected, got ${(result as? ExportOutcome.Failure)?.failure}",
            result.isSuccess(),
        )
        val media = (result as ExportOutcome.Success).media
        assertTrue(media.byteCount > 0)
        assertNotNull(media.ref.value)

        val finals = mediaDir.listFiles()
            ?.filter { it.isFile && it.name.startsWith("ewm_") && !it.name.contains(".ewm_tmp") }
            ?: emptyList()
        assertTrue("final ewm_ file must exist after success", finals.any { it.length() > 0 })
        assertFalse(
            "no temp leftovers after success",
            mediaDir.listFiles()?.any { it.name.contains(".ewm_tmp") } == true,
        )
    }
}
