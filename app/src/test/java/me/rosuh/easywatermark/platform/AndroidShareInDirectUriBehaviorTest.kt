package me.rosuh.easywatermark.platform

import android.app.Application
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.os.Looper
import androidx.core.content.FileProvider
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import java.io.File
import java.io.FileOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import me.rosuh.easywatermark.BuildConfig
import me.rosuh.easywatermark.data.model.WatermarkTileMode
import me.rosuh.easywatermark.data.repo.TemplateRepository
import me.rosuh.easywatermark.data.repo.UserConfigRepository
import me.rosuh.easywatermark.data.repo.WaterMarkRepository
import me.rosuh.easywatermark.ui.MainViewModel
import me.rosuh.easywatermark.utils.ktx.toMediaRef
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.koin.android.ext.koin.androidContext
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.module
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows
import org.robolectric.annotation.Config

/**
 * L1 behavior: production share-in enters Editor with original content URIs (no share_sources copy).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class AndroidShareInDirectUriBehaviorTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Before
    fun setUp() {
        val cacheField = FileProvider::class.java.getDeclaredField("sCache")
        cacheField.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        (cacheField.get(null) as MutableMap<*, *>).clear()
        stopKoin()
        val app = RuntimeEnvironment.getApplication()
        startKoin {
            androidContext(app)
            modules(
                module {
                    single<Context> { app }
                },
            )
        }
    }

    @After
    fun tearDown() {
        stopKoin()
    }

    private fun writePng(file: File, color: Int = 0xFF112233.toInt()): Uri {
        file.parentFile?.mkdirs()
        val bmp = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888)
        bmp.eraseColor(color)
        FileOutputStream(file).use { out ->
            assertTrue(bmp.compress(Bitmap.CompressFormat.PNG, 100, out))
        }
        bmp.recycle()
        val context = RuntimeEnvironment.getApplication()
        return FileProvider.getUriForFile(
            context,
            "${BuildConfig.APPLICATION_ID}.fileprovider",
            file,
        )
    }

    private fun newViewModel(): MainViewModel {
        val dir = temporaryFolder.newFolder("ds")
        val wmStore = PreferenceDataStoreFactory.create(
            produceFile = { File(dir, "wm.preferences_pb") },
        )
        val userStore = PreferenceDataStoreFactory.create(
            produceFile = { File(dir, "user.preferences_pb") },
        )
        val waterMarkRepo = WaterMarkRepository(
            dataStore = wmStore,
            defaultTextProvider = { "test" },
            tileModeFromStorageId = { WatermarkTileMode.fromStorageId(it) },
            logError = {},
        )
        val userRepo = UserConfigRepository(userStore)
        val templateRepo = TemplateRepository(templateDao = null, ioContext = Dispatchers.IO)
        return MainViewModel(userRepo, waterMarkRepo, templateRepo)
    }

    @Test
    fun enterEditorFromShareUris_keepsOriginalContentUris_noShareSourcesDir_andReadable() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val compressor = File(context.cacheDir, "compressor").apply { mkdirs() }
        val f1 = File(compressor, "share-a.png")
        val f2 = File(compressor, "share-b.png")
        val uri1 = writePng(f1, 0xFFAA0000.toInt())
        val uri2 = writePng(f2, 0xFF00AA00.toInt())
        // Dedup: same URI twice should collapse via toSet()
        val uri1Dup = Uri.parse(uri1.toString())

        val shareSources = File(context.filesDir, "share_sources")
        if (shareSources.exists()) {
            shareSources.deleteRecursively()
        }

        val vm = newViewModel()
        vm.userPreferences.first()
        vm.enterEditorFromShareUris(listOf(uri1, uri2, uri1Dup))
        // Drain Main + Default work from viewModelScope / generateImageInfoList.
        repeat(20) {
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            kotlinx.coroutines.delay(25)
            if (vm.launchScreenUiStateFlow.value.selectedImageList.isNotEmpty()) return@repeat
        }

        val launch = vm.launchScreenUiStateFlow.value
        val selected = launch.selectedImageList.map { it.uri.value }
        assertEquals("dedup must collapse duplicate URIs", 2, selected.size)
        assertEquals(
            listOf(uri1.toString(), uri2.toString()).toSet(),
            selected.toSet(),
        )
        // Order follows LinkedHashSet insertion from the filtered input list.
        assertEquals(uri1.toString(), selected[0])
        assertEquals(uri2.toString(), selected[1])

        assertFalse(
            "share-in must not create filesDir/share_sources copies",
            shareSources.exists() && (shareSources.listFiles()?.isNotEmpty() == true),
        )

        // Current-session decode/read via ContentResolver for each original URI.
        for (uri in listOf(uri1, uri2)) {
            val bytes = context.contentResolver.openInputStream(uri)!!.use { it.readBytes() }
            assertTrue("content URI must remain readable: $uri", bytes.isNotEmpty())
            assertEquals(0x89.toByte(), bytes[0]) // PNG signature
        }
    }
}
