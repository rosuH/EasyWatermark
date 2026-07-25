package me.rosuh.easywatermark.session

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import me.rosuh.easywatermark.data.datastore.createUserConfigDataStore
import me.rosuh.easywatermark.data.datastore.createWaterMarkDataStore
import me.rosuh.easywatermark.data.model.ImageInfo
import me.rosuh.easywatermark.data.model.MediaRef
import me.rosuh.easywatermark.data.model.WatermarkConfigChange
import me.rosuh.easywatermark.data.model.WatermarkTileMode
import me.rosuh.easywatermark.data.repo.UserConfigRepository
import me.rosuh.easywatermark.data.repo.WaterMarkRepository
import me.rosuh.easywatermark.ui.LaunchScreenUiState
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * E2 L3 — Desktop close/back policy:
 * Editor NavigateBack → Launch and **discards batch selection**; durable WaterMark config intact.
 * Structural: window close cancels export; no productRoute / selectedSessionImage reintroduction.
 */
class DesktopCloseBackPolicyTest {

    private fun newSession(dir: File): Pair<WatermarkSessionViewModel, WaterMarkRepository> {
        val water = WaterMarkRepository(
            dataStore = createWaterMarkDataStore(dir),
            defaultTextProvider = { "EasyWatermark" },
            tileModeFromStorageId = { WatermarkTileMode.fromStorageId(it) },
            logError = {},
        )
        val user = UserConfigRepository(createUserConfigDataStore(dir))
        val session = WatermarkSessionViewModel(
            waterMarkRepo = water,
            userConfigRepo = user,
            exportPipeline = null,
        )
        return session to water
    }

    @Test
    fun navigateBack_fromEditor_discardsBatch_keepsWaterMarkConfig() = runBlocking {
        val dir = File(System.getProperty("java.io.tmpdir"), "e2-desktop-back-${System.nanoTime()}")
        try {
            val (session, water) = newSession(dir)
            session.dispatchAndAwait(
                AppIntent.ApplyConfig(WatermarkConfigChange.Text("desktop-persist-marker")),
            )
            // Drain config sync.
            val beforeText = water.waterMark.first { it.text == "desktop-persist-marker" }.text

            val a = ImageInfo(uri = MediaRef("file:///a.jpg"), offsetX = 0.2f, offsetY = 0.8f)
            session.dispatchAndAwait(AppIntent.EnterEditor(selected = listOf(a)))
            assertEquals(LaunchScreenUiState.Editor, session.launchScreenUiStateFlow.value.uiState)
            assertEquals(1, session.launchScreenUiStateFlow.value.selectedImageList.size)

            session.dispatchAndAwait(AppIntent.NavigateBack)
            val launch = session.launchScreenUiStateFlow.value
            assertEquals(LaunchScreenUiState.Launch, launch.uiState)
            assertTrue(launch.selectedImageList.isEmpty(), "batch selection discarded")
            assertNull(launch.curImageInfo)

            val afterText = water.waterMark.first().text
            assertEquals(beforeText, afterText, "durable WaterMark config must survive batch discard")
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun desktopWindow_closePolicy_cancelsExport_noHostOwners() {
        val root = File(".").canonicalFile
        fun resolve(rel: String): File {
            var dir = root
            repeat(6) {
                val candidate = File(dir, rel)
                if (candidate.isFile) return candidate
                dir = dir.parentFile ?: return candidate
            }
            return File(root, rel)
        }
        val window = resolve("desktopApp/src/main/kotlin/me/rosuh/easywatermark/desktop/DesktopWindow.kt")
            .readText()
        assertTrue(
            "cancelExport" in window && "closeDesktopWindow" in window,
            "window close must cancel export via closeDesktopWindow",
        )
        assertFalse(
            Regex("""var\s+productRoute\s+by""").containsMatchIn(window),
            "must not reintroduce productRoute host owner",
        )
        assertFalse(
            Regex("""var\s+selectedSessionImage\s+by""").containsMatchIn(window) ||
                Regex("""selectedSessionImage\s*=""").containsMatchIn(window),
            "must not reintroduce selectedSessionImage",
        )
        assertTrue(
            "session.onBackPressed()" in window,
            "editor back must use Session NavigateBack",
        )
    }
}
