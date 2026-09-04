package me.rosuh.easywatermark.ui

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Permanent source guard for the Android one-off icon picker contract.
 *
 * `PickVisualMedia` grants access to the selected item. Routing this action through broad media or
 * legacy storage permission changes the product behavior and breaks the privacy contract.
 */
class IconPickerSourceContractTest {

    @Test
    fun iconPicker_launchesDirectly_withoutBroadMediaOrLegacyStoragePermission() {
        val sourceFile = locateIconOptionSource()
        val source = sourceFile.readText()

        assertTrue(
            "IconOption must keep using the system PickVisualMedia contract",
            source.contains("ActivityResultContracts.PickVisualMedia()"),
        )
        assertTrue(
            "The icon CTA must launch PickVisualMedia directly",
            source.contains("singlePhotoPickerLauncher.launch("),
        )
        assertFalse(
            "A one-off Photo Picker action must not request READ_MEDIA_IMAGES",
            source.contains("READ_MEDIA_IMAGES"),
        )
        assertFalse(
            "A one-off Photo Picker action must not request legacy storage permission",
            source.contains("READ_EXTERNAL_STORAGE"),
        )
        assertFalse(
            "IconOption must not own an accompanist permission state",
            source.contains("rememberPermissionState"),
        )
        assertFalse(
            "IconOption must not branch through a runtime permission request",
            source.contains("launchPermissionRequest"),
        )
    }

    @Test
    fun pickerResult_routesThroughDurableImport_beforeSharedConfigCommit() {
        val iconOption = locateSource(
            "src/main/java/me/rosuh/easywatermark/ui/compose/IconOption.kt",
        ).readText()
        val activity = locateSource(
            "src/main/java/me/rosuh/easywatermark/ui/MainActivity.kt",
        ).readText()
        val viewModel = locateSource(
            "src/main/java/me/rosuh/easywatermark/ui/MainViewModel.kt",
        ).readText()

        assertTrue(
            "The Android picker edge must pass the transient Uri to the durable import path",
            iconOption.contains("uri?.let(onIconPicked)"),
        )
        assertFalse(
            "IconOption must not persist the transient picker Uri directly",
            iconOption.contains("toMediaRef"),
        )
        assertTrue(
            "The production Activity must wire picker results to MainViewModel durable import",
            activity.contains("onIconPicked = viewModel::importWatermarkIcon"),
        )
        assertTrue(
            "MainViewModel must await the shared config commit after the private copy",
            viewModel.contains(
                "dispatchAndAwait(AppIntent.ApplyConfig(WatermarkConfigChange.Icon(ref)))",
            ),
        )
    }

    @Test
    fun coldStart_preservesPersistedDurableIconMode() {
        val application = locateSource(
            "src/main/java/me/rosuh/easywatermark/MyApp.kt",
        ).readText()

        assertFalse(
            "Cold start must not reset a persisted durable icon back to text mode",
            application.contains("resetModeToText()"),
        )
    }

    @Test
    fun editorEntry_readsPersistedWatermark_insteadOfInactiveStateMirror() {
        val viewModel = locateSource(
            "src/main/java/me/rosuh/easywatermark/ui/MainViewModel.kt",
        ).readText()

        assertFalse(
            "Editor entry must not read an inactive stateIn mirror whose value is WaterMark.default",
            viewModel.contains("waterMarkFlow.value"),
        )
        assertTrue(
            "Editor entry must read the persisted repository value",
            viewModel.contains(
                "private suspend fun persistedWaterMark(): WaterMark = " +
                    "waterMarkRepo.waterMark.first()",
            ),
        )
        assertTrue(
            "Every Android editor-entry path must use the persisted watermark snapshot",
            Regex("waterMark = persistedWaterMark\\(\\)")
                .findAll(viewModel)
                .count() >= 3,
        )
    }

    private fun locateIconOptionSource(): File {
        return locateSource("src/main/java/me/rosuh/easywatermark/ui/compose/IconOption.kt")
    }

    private fun locateSource(relative: String): File {
        val cwd = File(System.getProperty("user.dir")!!)
        val candidates = linkedSetOf(
            File(cwd, relative),
            File(cwd, "app/$relative"),
            File(cwd.parentFile ?: cwd, "app/$relative"),
        )
        return candidates.firstOrNull { it.isFile }
            ?: error("IconOption.kt not found from user.dir=$cwd candidates=$candidates")
    }
}
