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
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.Continuation
import kotlin.coroutines.intrinsics.suspendCoroutineUninterceptedOrReturn
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withTimeout
import me.rosuh.easywatermark.BuildConfig
import me.rosuh.easywatermark.data.model.MediaRef
import me.rosuh.easywatermark.data.model.ImageInfo
import me.rosuh.easywatermark.data.model.WatermarkTileMode
import me.rosuh.easywatermark.data.repo.TemplateRepository
import me.rosuh.easywatermark.data.repo.UserConfigRepository
import me.rosuh.easywatermark.data.repo.WaterMarkRepository
import me.rosuh.easywatermark.session.MediaLibraryPort
import me.rosuh.easywatermark.session.WatermarkSessionViewModel
import me.rosuh.easywatermark.ui.Action
import me.rosuh.easywatermark.ui.Image
import me.rosuh.easywatermark.ui.LaunchScreenState
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
        )
        val userRepo = UserConfigRepository(userStore)
        val templateRepo = TemplateRepository(templateDao = null, ioContext = Dispatchers.IO)
        return MainViewModel(userRepo, waterMarkRepo, templateRepo)
    }

    private suspend fun awaitSelection(vm: MainViewModel, expected: List<Uri>) {
        repeat(40) {
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            if (vm.launchScreenUiStateFlow.value.selectedImageList.map { it.uri.value } ==
                expected.map { it.toString() }
            ) return
            kotlinx.coroutines.delay(25)
        }
        assertEquals(
            expected.map { it.toString() },
            vm.launchScreenUiStateFlow.value.selectedImageList.map { it.uri.value },
        )
    }

    @Test
    fun systemPicker_replacesOnFresh_appendsDeduplicatedAndPreservesFocus_emptyIsNoOp() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val dir = File(context.cacheDir, "compressor/picker-selection").apply { mkdirs() }
        val a = writePng(File(dir, "a.png"))
        val b = writePng(File(dir, "b.png"))
        val c = writePng(File(dir, "c.png"))
        val vm = newViewModel()
        vm.userPreferences.first()

        vm.process(Action.SystemPickerImageSelected(listOf(a)))
        awaitSelection(vm, listOf(a))
        assertEquals(a.toString(), vm.launchScreenUiStateFlow.value.curImageInfo?.uri?.value)

        vm.process(Action.SystemPickerImageSelected(listOf(b)))
        awaitSelection(vm, listOf(b))
        assertEquals(
            "fresh picker selection replaces the old focus",
            b.toString(),
            vm.launchScreenUiStateFlow.value.curImageInfo?.uri?.value,
        )

        vm.process(Action.SystemPickerImageSelected(listOf(b, c), append = true))
        awaitSelection(vm, listOf(b, c))
        assertEquals(
            "append keeps the existing focus",
            b.toString(),
            vm.launchScreenUiStateFlow.value.curImageInfo?.uri?.value,
        )

        val before = vm.launchScreenUiStateFlow.value
        vm.process(Action.SystemPickerImageSelected(emptyList(), append = true))
        kotlinx.coroutines.delay(50)
        val after = vm.launchScreenUiStateFlow.value
        assertEquals(before.selectedImageList.map { it.uri }, after.selectedImageList.map { it.uri })
        assertEquals(before.curImageInfo?.uri, after.curImageInfo?.uri)
    }

    @Test
    fun selectGallery_appendRetainsFocusAndDeduplicatesUris() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val dir = File(context.cacheDir, "compressor/gallery-selection").apply { mkdirs() }
        val a = writePng(File(dir, "a.png"))
        val b = writePng(File(dir, "b.png"))
        val vm = newViewModel()
        vm.userPreferences.first()
        vm.process(Action.SystemPickerImageSelected(listOf(a)))
        awaitSelection(vm, listOf(a))

        fun galleryImage(uri: Uri) = Image(0, uri.toMediaRef(), uri.lastPathSegment.orEmpty(), 1L, 0L)
        vm.selectGallery(listOf(galleryImage(a), galleryImage(b)), append = true)
        awaitSelection(vm, listOf(a, b))
        assertEquals(a.toString(), vm.launchScreenUiStateFlow.value.curImageInfo?.uri?.value)
    }

    @Test
    fun selectGallery_freshReplacesThenAppendPreservesSelection() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val dir = File(context.cacheDir, "compressor/gallery-fresh").apply { mkdirs() }
        val a = writePng(File(dir, "a.png"))
        val b = writePng(File(dir, "b.png"))
        val c = writePng(File(dir, "c.png"))
        val vm = newViewModel()
        vm.process(Action.SystemPickerImageSelected(listOf(a)))
        awaitSelection(vm, listOf(a))

        fun galleryImage(uri: Uri) = Image(0, uri.toMediaRef(), uri.lastPathSegment.orEmpty(), 1L, 0L)
        vm.selectGallery(listOf(galleryImage(b), galleryImage(b)))
        awaitSelection(vm, listOf(b))
        assertEquals(b.toMediaRef(), vm.launchScreenUiStateFlow.value.curImageInfo?.uri)
        vm.selectGallery(listOf(galleryImage(c), galleryImage(b)), append = true)
        awaitSelection(vm, listOf(b, c))
        assertEquals(b.toMediaRef(), vm.launchScreenUiStateFlow.value.curImageInfo?.uri)
    }

    @Test
    fun pickerAppend_waitingForPublicationRetainsLatestOffset() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val dir = File(context.cacheDir, "compressor/picker-publish-race").apply { mkdirs() }
        val a = writePng(File(dir, "a.png"))
        val b = writePng(File(dir, "b.png"))
        val vm = newViewModel()
        vm.process(Action.SystemPickerImageSelected(listOf(a)))
        awaitSelection(vm, listOf(a))
        val before = vm.launchScreenUiStateFlow.value
        val mutexField = WatermarkSessionViewModel::class.java.getDeclaredField("sessionMutex")
        mutexField.isAccessible = true
        val mutex = mutexField.get(vm) as Mutex
        val commitMethod = MainViewModel::class.java.getDeclaredMethod(
            "commitPickedImages", List::class.java, List::class.java,
            Boolean::class.javaPrimitiveType, LaunchScreenState::class.java, Continuation::class.java,
        ).apply { isAccessible = true }

        withTimeout(2_000) {
            while (!mutex.tryLock()) {
                Shadows.shadowOf(Looper.getMainLooper()).idle()
                kotlinx.coroutines.delay(25)
            }
        }
        val commit = async(start = CoroutineStart.UNDISPATCHED) {
            suspendCoroutineUninterceptedOrReturn<Any?> { continuation ->
                commitMethod.invoke(vm, listOf(ImageInfo(b.toMediaRef())), emptyList<Image>(), true, before, continuation)
            }
        }
        try {
            assertFalse("append must wait behind the existing publication", commit.isCompleted)
            vm.applyOffset(before.selectedImageList.single().copy(offsetX = 0.2f, offsetY = 0.7f))
        } finally {
            mutex.unlock()
        }
        try {
            awaitSelection(vm, listOf(a, b))
            withTimeout(2_000) {
                while (!commit.isCompleted) {
                    Shadows.shadowOf(Looper.getMainLooper()).idle()
                    kotlinx.coroutines.delay(25)
                }
            }
            commit.await()
            val after = vm.launchScreenUiStateFlow.value
            assertEquals(0.2f, after.selectedImageList.first().offsetX)
            assertEquals(0.7f, after.curImageInfo!!.offsetY)
            assertTrue(after.curImageInfo === after.selectedImageList.first())
        } finally {
            commit.cancel()
            Shadows.shadowOf(Looper.getMainLooper()).idle()
        }
    }

    @Test
    fun cancelledOlderPickerImport_doesNotPublishAfterNewerPicker() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val dir = File(context.cacheDir, "compressor/picker-cancel-race").apply { mkdirs() }
        val initial = writePng(File(dir, "initial.png"))
        val stale = writePng(File(dir, "stale.png"))
        val latest = writePng(File(dir, "latest.png"))
        val vm = newViewModel()
        vm.userPreferences.first()

        vm.process(Action.SystemPickerImageSelected(listOf(initial)))
        awaitSelection(vm, listOf(initial))

        val firstEnrichmentStarted = CompletableDeferred<Unit>()
        val enrichmentCalls = AtomicInteger()
        val fakeLibrary = object : MediaLibraryPort {
            override suspend fun listImages() = emptyList<Image>()

            override suspend fun enrichPickerRefs(refs: List<MediaRef>): List<Image> {
                if (enrichmentCalls.incrementAndGet() == 1) {
                    firstEnrichmentStarted.complete(Unit)
                    awaitCancellation()
                }
                return emptyList()
            }
        }
        val libraryField = WatermarkSessionViewModel::class.java.getDeclaredField("mediaLibrary")
        libraryField.isAccessible = true
        libraryField.set(vm, fakeLibrary)

        vm.process(Action.SystemPickerImageSelected(listOf(stale), append = true))
        withTimeout(2_000) { firstEnrichmentStarted.await() }
        vm.process(Action.SystemPickerImageSelected(listOf(latest), append = true))

        awaitSelection(vm, listOf(initial, latest))
        kotlinx.coroutines.delay(100)
        assertEquals(
            listOf(initial.toString(), latest.toString()),
            vm.launchScreenUiStateFlow.value.selectedImageList.map { it.uri.value },
        )
        assertEquals(initial.toString(), vm.launchScreenUiStateFlow.value.curImageInfo?.uri?.value)
        assertEquals(2, enrichmentCalls.get())
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
