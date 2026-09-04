@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class, kotlinx.cinterop.ExperimentalForeignApi::class)

package me.rosuh.easywatermark.session

import androidx.lifecycle.ViewModelStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import me.rosuh.easywatermark.data.datastore.createUserConfigDataStore
import me.rosuh.easywatermark.data.datastore.createWaterMarkDataStore
import me.rosuh.easywatermark.data.model.ImageInfo
import me.rosuh.easywatermark.data.model.MediaRef
import me.rosuh.easywatermark.data.model.WaterMark
import me.rosuh.easywatermark.data.model.WatermarkTileMode
import me.rosuh.easywatermark.data.repo.UserConfigRepository
import me.rosuh.easywatermark.data.repo.WaterMarkRepository
import platform.Foundation.NSUUID
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Ready-only publication uses the **existing** [WatermarkSessionViewModel.publishEditorSelectionIf]
 * gate only — no common empty-selection API expansion (allowlist / E3).
 */
class IosReadySelectionTransactionTest {
    private val mainDispatcher = UnconfinedTestDispatcher()

    @BeforeTest
    fun setMain() = Dispatchers.setMain(mainDispatcher)

    @AfterTest
    fun resetMain() = Dispatchers.resetMain()

    @Test
    fun readyOnlyPublication_replacesSessionAtomically_andStaleStillValidIsRejected() =
        runTest(mainDispatcher) {
            val id = NSUUID().UUIDString()
            val watermarks = WaterMarkRepository(
                createWaterMarkDataStore(name = "ready_tx_wm_$id"),
                defaultTextProvider = { "watermark" },
                tileModeFromStorageId = { WatermarkTileMode.fromStorageId(it) },
                logError = {},
            )
            val session = WatermarkSessionViewModel(
                waterMarkRepo = watermarks,
                userConfigRepo = UserConfigRepository(createUserConfigDataStore(name = "ready_tx_uc_$id")),
                exportPipeline = null,
            )
            val store = ViewModelStore().also { it.put("session-$id", session) }
            try {
                assertTrue(
                    session.publishEditorSelectionIf(
                        stillValid = { true },
                        selected = listOf(ImageInfo(MediaRef("/tmp/ready"), width = 120, height = 80)),
                        waterMark = WaterMark.default,
                    ),
                )
                assertEquals(
                    listOf("/tmp/ready"),
                    session.launchScreenUiStateFlow.first().selectedImageList.map { it.uri.value },
                )

                // Stale stillValid must not replace a published Ready set.
                assertFalse(
                    session.publishEditorSelectionIf(
                        stillValid = { false },
                        selected = listOf(ImageInfo(MediaRef("/tmp/stale"), width = 1, height = 1)),
                        waterMark = WaterMark.default,
                    ),
                )
                assertEquals(
                    listOf("/tmp/ready"),
                    session.launchScreenUiStateFlow.first().selectedImageList.map { it.uri.value },
                )

                // Newer non-empty replace (atomic under Session mutex).
                assertTrue(
                    session.publishEditorSelectionIf(
                        stillValid = { true },
                        selected = listOf(ImageInfo(MediaRef("/tmp/gen2"), width = 20, height = 20)),
                        waterMark = WaterMark.default,
                    ),
                )
                assertEquals(
                    listOf("/tmp/gen2"),
                    session.launchScreenUiStateFlow.first().selectedImageList.map { it.uri.value },
                )
            } finally {
                store.clear()
            }
        }
}
