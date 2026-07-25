package me.rosuh.easywatermark.platform

import android.app.Application
import android.net.Uri
import androidx.core.content.FileProvider
import java.io.File
import java.io.FileInputStream
import kotlinx.coroutines.runBlocking
import me.rosuh.easywatermark.BuildConfig
import me.rosuh.easywatermark.data.model.MediaRef
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * E2 L1 — durable share stage: app-owned readable ref survives source deletion.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class AndroidShareStagingTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private val authority = "me.rosuh.easywatermark.debug.fileprovider"

    @Test
    fun copyToOwnedRef_publishesReadableAppOwnedRef_survivesSourceDeletion() = runBlocking {
        val source = temporaryFolder.newFile("share-src.png").apply {
            writeBytes(byteArrayOf(9, 8, 7, 6, 5))
        }
        val stageDir = temporaryFolder.newFolder("share_sources")
        val staging = AndroidShareStaging(
            stageDirectory = stageDir,
            authority = authority,
            openSource = { FileInputStream(source) },
            sourceMimeType = { "image/png" },
            contentUriForFile = { file ->
                Uri.parse("content://$authority/share_sources/${file.name}")
            },
            nextId = { "abc123" },
        )

        val ref = staging.copyToOwnedRef(Uri.parse("content://picker/share/1")).getOrThrow()
        assertTrue(source.delete())

        assertTrue(ref.value.startsWith("content://$authority/share_sources/share-"))
        assertTrue(staging.isOwnedReadable(ref))
        val owned = stageDir.listFiles()!!.single { it.name.startsWith("share-") }
        assertArrayEquals(byteArrayOf(9, 8, 7, 6, 5), owned.readBytes())
        assertTrue(staging.deleteIfOwned(ref))
        assertFalse(staging.isOwnedReadable(ref))
    }

    @Test
    fun productionConstructor_fileProviderPath_isReadableThroughContentResolver() = runBlocking {
        val context: Application = RuntimeEnvironment.getApplication()
        val sourceDirectory = File(context.cacheDir, "compressor").apply { mkdirs() }
        val source = File(sourceDirectory, "share-source.png").apply {
            writeBytes(byteArrayOf(2, 4, 6, 8))
        }
        val sourceUri = FileProvider.getUriForFile(
            context,
            "${BuildConfig.APPLICATION_ID}.fileprovider",
            source,
        )
        val staging = AndroidShareStaging(context)

        val ref = staging.copyToOwnedRef(sourceUri).getOrThrow()
        val uri = Uri.parse(ref.value)
        val bytes = context.contentResolver.openInputStream(uri)!!.use { it.readBytes() }

        assertEquals("${BuildConfig.APPLICATION_ID}.fileprovider", uri.authority)
        assertEquals("share_sources", uri.pathSegments.first())
        assertArrayEquals(byteArrayOf(2, 4, 6, 8), bytes)
        assertTrue(staging.deleteIfOwned(ref))
    }

    @Test
    fun copyAllToOwnedRefs_failsClosed_andCleansPartial() = runBlocking {
        val stageDir = temporaryFolder.newFolder("share_partial")
        val good = temporaryFolder.newFile("good.png").apply { writeBytes(byteArrayOf(1)) }
        var calls = 0
        val staging = AndroidShareStaging(
            stageDirectory = stageDir,
            authority = authority,
            openSource = {
                calls++
                if (calls == 1) FileInputStream(good) else null
            },
            sourceMimeType = { "image/png" },
            contentUriForFile = { file ->
                Uri.parse("content://$authority/share_sources/${file.name}")
            },
            nextId = { "id$calls" },
        )

        val result = staging.copyAllToOwnedRefs(
            listOf(
                Uri.parse("content://a/1"),
                Uri.parse("content://a/2"),
            ),
        )
        assertTrue(result.isFailure)
        assertTrue(stageDir.listFiles().orEmpty().none { it.name.startsWith("share-") })
    }
}
