package me.rosuh.easywatermark.platform

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import android.app.Application

/**
 * L1: share-in must not create app-owned `share_sources` copies or process-death restore stores.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class AndroidShareInDirectUriTest {

    @Test
    fun productionSources_doNotReferenceShareStagingOrRestoreStore() {
        val cwd = File(System.getProperty("user.dir")!!).canonicalFile
        val moduleRoots = listOf(cwd, cwd.parentFile, File(cwd, "app"), File(cwd.parentFile, "app"))
        val mainJava = moduleRoots
            .map { File(it, "src/main/java/me/rosuh/easywatermark") }
            .firstOrNull { it.isDirectory }
            ?: error("main java tree not found from cwd=$cwd")
        val mainRes = moduleRoots
            .map { File(it, "src/main/res/xml/filepaths.xml") }
            .firstOrNull { it.isFile }
            ?: error("filepaths.xml not found from cwd=$cwd")

        fun read(rel: String): String {
            val f = File(mainJava, rel)
            assertTrue("missing $rel under $mainJava", f.isFile)
            return f.readText()
        }

        val vm = read("ui/MainViewModel.kt")
        val activity = read("ui/ComposeMainActivity.kt")
        val filepaths = mainRes.readText()

        assertFalse(File(mainJava, "platform/AndroidShareStaging.kt").exists())
        assertFalse(File(mainJava, "platform/AndroidSessionRestoreStore.kt").exists())
        assertFalse("AndroidShareStaging" in vm)
        assertFalse("AndroidSessionRestoreStore" in vm)
        assertFalse("stageShareAndEnterEditor" in vm)
        assertFalse("restoreEditorIfDurable" in vm)
        assertTrue("enterEditorFromShareUris" in vm)
        assertTrue("enterEditorFromShareUris" in activity)
        assertFalse("restoreEditorIfDurable" in activity)
        assertFalse("share_sources" in filepaths)
    }
}
