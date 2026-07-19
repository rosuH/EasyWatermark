package me.rosuh.easywatermark.platform

import android.app.Application
import android.net.Uri
import androidx.core.content.FileProvider
import java.io.File
import java.io.FileInputStream
import java.util.ArrayDeque
import kotlinx.coroutines.runBlocking
import me.rosuh.easywatermark.BuildConfig
import me.rosuh.easywatermark.data.model.MediaRef
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class AndroidIconPersistenceTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private val authority = "me.rosuh.easywatermark.debug.fileprovider"

    @Test
    fun productionConstructor_fileProviderPath_isReadableThroughContentResolver() = runBlocking {
        val context: Application = RuntimeEnvironment.getApplication()
        val sourceDirectory = File(context.cacheDir, "compressor").apply { mkdirs() }
        val source = File(sourceDirectory, "picker-source.png").apply {
            writeBytes(byteArrayOf(2, 3, 5, 7, 11))
        }
        val sourceUri = FileProvider.getUriForFile(
            context,
            "${BuildConfig.APPLICATION_ID}.fileprovider",
            source,
        )
        val persistence = AndroidIconPersistence(context)

        val ref = persistence.copyToOwnedRef(sourceUri).getOrThrow()
        val uri = Uri.parse(ref.value)
        val bytes = context.contentResolver.openInputStream(uri)!!.use { it.readBytes() }

        assertEquals("${BuildConfig.APPLICATION_ID}.fileprovider", uri.authority)
        assertEquals("watermark_icons", uri.pathSegments.first())
        assertArrayEquals(byteArrayOf(2, 3, 5, 7, 11), bytes)
        assertTrue(persistence.deleteIfOwned(ref))
    }

    @Test
    fun copyToOwnedRef_publishesExactBytes_thatSurviveSourceDeletion() = runBlocking {
        val source = temporaryFolder.newFile("source.png").apply {
            writeBytes(byteArrayOf(1, 4, 9, 16, 25))
        }
        val iconDirectory = temporaryFolder.newFolder("icons")
        val persistence = persistence(iconDirectory, source, mimeType = "image/png")

        val ref = persistence.copyToOwnedRef(Uri.parse("content://picker/source/1")).getOrThrow()
        assertTrue(source.delete())

        val owned = iconDirectory.singlePublishedFile()
        assertEquals("png", owned.extension)
        assertArrayEquals(byteArrayOf(1, 4, 9, 16, 25), owned.readBytes())
        assertTrue(ref.value.startsWith("content://$authority/watermark_icons/icon-"))
    }

    @Test
    fun copyToOwnedRef_unreadableOrEmptySource_leavesNoFiles() = runBlocking {
        val unreadableDirectory = temporaryFolder.newFolder("unreadable")
        val unreadable = persistence(unreadableDirectory, source = null)
        assertTrue(unreadable.copyToOwnedRef(Uri.parse("content://picker/missing")).isFailure)
        assertTrue(unreadableDirectory.listFiles().orEmpty().isEmpty())

        val emptySource = temporaryFolder.newFile("empty.png")
        val emptyDirectory = temporaryFolder.newFolder("empty")
        val empty = persistence(emptyDirectory, source = emptySource)
        assertTrue(empty.copyToOwnedRef(Uri.parse("content://picker/empty")).isFailure)
        assertTrue(emptyDirectory.listFiles().orEmpty().isEmpty())
    }

    @Test
    fun copyToOwnedRef_replacementsHaveDistinctRefs() = runBlocking {
        val source = temporaryFolder.newFile("source.jpg").apply { writeBytes(byteArrayOf(7)) }
        val iconDirectory = temporaryFolder.newFolder("distinct")
        val persistence = persistence(
            iconDirectory = iconDirectory,
            source = source,
            ids = ArrayDeque(listOf("first", "second")),
        )

        val first = persistence.copyToOwnedRef(Uri.parse("content://picker/1")).getOrThrow()
        val second = persistence.copyToOwnedRef(Uri.parse("content://picker/2")).getOrThrow()

        assertNotEquals(first, second)
        assertEquals(2, iconDirectory.publishedFiles().size)
    }

    @Test
    fun deleteIfOwned_rejectsExternalMalformedAndTraversalRefs() = runBlocking {
        val source = temporaryFolder.newFile("source.jpg").apply { writeBytes(byteArrayOf(3)) }
        val iconDirectory = temporaryFolder.newFolder("ownership")
        val persistence = persistence(iconDirectory, source)
        val owned = persistence.copyToOwnedRef(Uri.parse("content://picker/1")).getOrThrow()
        val ownedFile = iconDirectory.singlePublishedFile()

        assertFalse(persistence.deleteIfOwned(MediaRef("content://external.provider/item/1")))
        assertFalse(
            persistence.deleteIfOwned(
                MediaRef("content://$authority/watermark_icons/../${ownedFile.name}"),
            ),
        )
        assertFalse(
            persistence.deleteIfOwned(
                MediaRef("content://$authority/not_watermark_icons/${ownedFile.name}"),
            ),
        )
        assertTrue(ownedFile.exists())

        assertTrue(persistence.deleteIfOwned(owned))
        assertFalse(ownedFile.exists())
    }

    @Test
    fun pruneExcept_keepsCurrentOwnedRef_andRemovesOnlyManagedOrphans() = runBlocking {
        val source = temporaryFolder.newFile("source.jpg").apply { writeBytes(byteArrayOf(8)) }
        val iconDirectory = temporaryFolder.newFolder("prune")
        val persistence = persistence(
            iconDirectory = iconDirectory,
            source = source,
            ids = ArrayDeque(listOf("keep", "orphan")),
        )
        val keep = persistence.copyToOwnedRef(Uri.parse("content://picker/1")).getOrThrow()
        persistence.copyToOwnedRef(Uri.parse("content://picker/2")).getOrThrow()
        val unrelated = File(iconDirectory, "user-file.txt").apply { writeText("leave me") }

        persistence.pruneExcept(keep)

        assertEquals(listOf("icon-keep.jpg"), iconDirectory.publishedFiles().map { it.name })
        assertTrue(unrelated.exists())
    }

    private fun persistence(
        iconDirectory: File,
        source: File?,
        mimeType: String = "image/jpeg",
        ids: ArrayDeque<String> = ArrayDeque(listOf("fixed")),
    ): AndroidIconPersistence = AndroidIconPersistence(
        iconDirectory = iconDirectory,
        authority = authority,
        openSource = { source?.let(::FileInputStream) },
        sourceMimeType = { mimeType },
        contentUriForFile = { file ->
            Uri.Builder()
                .scheme("content")
                .authority(authority)
                .appendPath("watermark_icons")
                .appendPath(file.name)
                .build()
        },
        nextId = { ids.removeFirst() },
    )

    private fun File.publishedFiles(): List<File> =
        listFiles().orEmpty().filter { it.name.startsWith("icon-") }.sortedBy { it.name }

    private fun File.singlePublishedFile(): File = publishedFiles().single()
}
