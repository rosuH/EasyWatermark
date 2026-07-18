package me.rosuh.easywatermark.desktop

import kotlinx.coroutines.flow.first
import me.rosuh.easywatermark.data.datastore.createUserConfigDataStore
import me.rosuh.easywatermark.data.datastore.createWaterMarkDataStore
import me.rosuh.easywatermark.data.model.ImageFormat
import me.rosuh.easywatermark.data.model.WaterMark
import me.rosuh.easywatermark.data.model.WatermarkTileMode
import me.rosuh.easywatermark.data.repo.UserConfigRepository
import me.rosuh.easywatermark.data.repo.WaterMarkRepository
import me.rosuh.easywatermark.domain.WatermarkConfigEditor
import me.rosuh.easywatermark.render.DesktopRenderSaveSpine
import me.rosuh.easywatermark.render.DesktopSaveDecision
import me.rosuh.easywatermark.render.DesktopWatermarkComposer
import java.io.File

/**
 * Desktop open → edit → render → save orchestration for `--headless` and Compose Desktop window.
 *
 * **Render/write** is delegated to [DesktopRenderSaveSpine] (shared with
 * [me.rosuh.easywatermark.session.DesktopExportPipelinePort]). This object owns repository reads,
 * Fixture generation, default output path ([defaultOutputFile]), and [SaveOutcome] presentation. *
 * Destination policies remain caller-side: preview temp, Save As exact path, and headless default
 * all pass an exact [File] into the spine (or use [defaultOutputFile] when null).
 */
object DesktopWatermarkFlow {

    /** Repo-local watermark-config store dir (NOT `$HOME`) — shared with the S4d-120 flow. */
    val storeDir: File = File("build/s4d120-desktop-watermark-config")

    /** Repo-local **output-prefs** store dir — separate from the witness store, never written here
 * (the flow only READS it), so an empty store keeps yielding the shared `(JPEG, 80)` default. */
    val userConfigStoreDir: File = File("build/s4d128-desktop-userconfig")

    /** Output dir (repo-local `build/`); the filename extension follows the chosen [ImageFormat]. */
    val outputDir: File = File("build/s4d120-desktop-headless")

    /** Default output file for [format]: JPEG → `.jpg`, PNG → `.png` (filename via [DesktopSaveDecision]). */
    fun defaultOutputFile(format: ImageFormat): File =
        File(outputDir, DesktopSaveDecision.defaultOutputFileName(format))

    /**
 * Build ONE common [WaterMarkRepository] over the desktop watermark-config store. DataStore forbids a
 * Second active store for the same file, so the caller retains a single instance per process (the * window remembers one; `runHeadless` builds one).
     */
    fun buildRepository(dir: File = storeDir): WaterMarkRepository = WaterMarkRepository(
        dataStore = createWaterMarkDataStore(dir = dir),
        defaultTextProvider = { "EasyWatermark 水印" },
        // Desktop uses the PURE storage-id mapper; the Android SDK-gated legacy DECAL-id-3→REPEAT mapper
        // is Android-only (there is no legacy desktop data).
        tileModeFromStorageId = { WatermarkTileMode.fromStorageId(it) },
        logError = { println("WaterMarkRepository: $it") },
    )

    /** Build ONE common [UserConfigRepository] over the desktop output-prefs store (single-instance-per-file). */
    fun buildUserConfigRepository(dir: File = userConfigStoreDir): UserConfigRepository =
        UserConfigRepository(createUserConfigDataStore(dir = dir))

    /** Structured result of one save-flow run (for console printing AND window status display). */
    data class SaveOutcome(
        val configInitial: String,
        val configAfterEdit: String,
        val inputLabel: String,
        val inputByteCount: Int,
        val outputPath: String,
        val format: ImageFormat,
        val width: Int,
        val height: Int,
        val outputByteCount: Int,
    )

    /**
 * Persist-path orchestration: resolve input bytes (caller or fixture), read prefs + watermark,
 * Render/write via [DesktopRenderSaveSpine] to [outputFile] or [defaultOutputFile]. * The `editor` param is retained for call-site stability (callers still hold one for their edits).
     */
    suspend fun runSaveFlow(
        repo: WaterMarkRepository,
        editor: WatermarkConfigEditor,
        userConfigRepo: UserConfigRepository,
        inputBytes: ByteArray? = null,
        inputLabel: String = "<generated 640x480 fixture>",
        outputFile: File? = null,
    ): SaveOutcome {
        val bytes = if (DesktopSaveDecision.usesCallerInput(inputBytes)) {
            inputBytes!!
        } else {
            DesktopWatermarkComposer.sampleBackgroundPng(width = 640, height = 480)
        }
        val prefs = userConfigRepo.userPreferences.first() // empty store -> (JPEG, 80) (shared default)
        val initial = repo.waterMark.first()
        // Render the PERSISTED WaterMark as-is for BOTH modes — no forced demo edit.
        val wm: WaterMark = initial
        val target = outputFile ?: defaultOutputFile(prefs.outputFormat)
        val saved = DesktopRenderSaveSpine.renderAndSave(
            imageBytes = bytes,
            config = wm,
            prefs = prefs,
            target = target,
        )
        return SaveOutcome(
            configInitial = describe(initial),
            configAfterEdit = describe(wm),
            inputLabel = inputLabel,
            inputByteCount = bytes.size,
            outputPath = saved.output.value,
            format = saved.format,
            width = saved.width,
            height = saved.height,
            outputByteCount = saved.outputByteCount,
        )
    }

    private fun describe(wm: WaterMark): String =
        "mode=${wm.markMode} text='${wm.text}' size=${wm.textSize} degree=${wm.degree} tile=${wm.tileMode} " +
            "hGap=${wm.hGap} vGap=${wm.vGap} alpha=${wm.alpha} " +
            "color=0x${(wm.textColor.toLong() and 0xFFFFFFFFL).toString(16).uppercase()} " +
            "typeface=${wm.textTypeface::class.simpleName} style=${wm.textStyle::class.simpleName} " +
            "icon='${wm.iconUri.value}'"
}
