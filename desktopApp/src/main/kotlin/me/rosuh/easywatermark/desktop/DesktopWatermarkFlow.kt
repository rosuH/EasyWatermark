package me.rosuh.easywatermark.desktop

import kotlinx.coroutines.flow.first
import me.rosuh.easywatermark.data.datastore.createWaterMarkDataStore
import me.rosuh.easywatermark.data.model.WaterMark
import me.rosuh.easywatermark.data.model.WatermarkTileMode
import me.rosuh.easywatermark.data.repo.WaterMarkRepository
import me.rosuh.easywatermark.domain.WatermarkConfigEditor
import me.rosuh.easywatermark.render.DesktopWatermarkComposer
import java.io.File

/**
 * S4d-121: the S4d-120 headless save spine extracted into reusable Desktop-app functions so BOTH the
 * `--headless` automation path ([runHeadless] in `Main.kt`) and the Compose Desktop window
 * ([launchDesktopWindow]) drive the **same** open → edit → render → save flow over the committed shared
 * APIs — the window does NOT fake a preview.
 *
 * S4d-122: this flow now passes `WaterMark.textColor`, `textTypeface`, and `textStyle` into
 * [DesktopWatermarkComposer.composeOverRealImage]. `textColor` and `textTypeface` are **raster-honored**;
 * the `textStyle` mapping is wired (like iOS S4d-113) but currently **inert** at the raster (commonMain
 * `composeTextCell` drops `drawStyle` — see `DesktopTextParityTest`). Still out of scope: icon watermark
 * and output-format/compress (PNG only).
 */
object DesktopWatermarkFlow {

    /** Repo-local watermark-config store dir (NOT `$HOME`) — shared with the S4d-120 flow. */
    val storeDir: File = File("build/s4d120-desktop-watermark-config")

    /** Default output PNG path (repo-local `build/`). */
    val defaultOutputFile: File = File("build/s4d120-desktop-headless/watermarked.png")

    /**
     * Build ONE common [WaterMarkRepository] over the desktop watermark-config store. DataStore forbids a
     * second active store for the same file, so the caller retains a single instance per process (the
     * window remembers one; `runHeadless` builds one).
     */
    fun buildRepository(dir: File = storeDir): WaterMarkRepository = WaterMarkRepository(
        dataStore = createWaterMarkDataStore(dir = dir),
        defaultTextProvider = { "EasyWatermark 水印" },
        // Desktop uses the PURE storage-id mapper; the Android SDK-gated legacy DECAL-id-3→REPEAT mapper
        // is Android-only (there is no legacy desktop data).
        tileModeFromStorageId = { WatermarkTileMode.fromStorageId(it) },
        logError = { println("WaterMarkRepository: $it") },
    )

    /** Structured result of one save-flow run (for console printing AND window status display). */
    data class SaveOutcome(
        val configInitial: String,
        val configAfterEdit: String,
        val inputLabel: String,
        val inputByteCount: Int,
        val outputPath: String,
        val width: Int,
        val height: Int,
        val pngByteCount: Int,
    )

    /**
     * Persist a config edit through [editor], re-read the persisted [WaterMark], render [inputBytes]
     * (or the deterministic 640×480 fixture) via [DesktopWatermarkComposer.composeOverRealImage] driven by
     * the persisted config (honored fields only), and write the PNG to [outputFile]. `suspend` (DataStore
     * reads) — the caller picks the dispatcher (`runBlocking` for headless; a UI coroutine for the window).
     */
    suspend fun runSaveFlow(
        repo: WaterMarkRepository,
        editor: WatermarkConfigEditor,
        inputBytes: ByteArray? = null,
        inputLabel: String = "<generated 640x480 fixture>",
        outputFile: File = defaultOutputFile,
    ): SaveOutcome {
        val bytes = inputBytes ?: DesktopWatermarkComposer.sampleBackgroundPng(width = 640, height = 480)
        val initial = repo.waterMark.first()
        editor.updateText("请勿转载 DO NOT REDISTRIBUTE")
        editor.updateDegree(330f)
        val wm = repo.waterMark.first()
        val result = DesktopWatermarkComposer.composeOverRealImage(
            imageBytes = bytes,
            text = wm.text,
            tileMode = wm.tileMode,
            textSize = wm.textSize,
            degree = wm.degree,
            hGapPercent = wm.hGap,
            vGapPercent = wm.vGap,
            alpha = wm.alpha / 255f,
            // S4d-122: drive the persisted text color / typeface / paint style.
            colorArgb = wm.textColor,
            typeface = wm.textTypeface,
            textStyle = wm.textStyle,
        )
        outputFile.parentFile?.mkdirs()
        outputFile.writeBytes(result.png)
        return SaveOutcome(
            configInitial = describe(initial),
            configAfterEdit = describe(wm),
            inputLabel = inputLabel,
            inputByteCount = bytes.size,
            outputPath = outputFile.path,
            width = result.width,
            height = result.height,
            pngByteCount = result.png.size,
        )
    }

    private fun describe(wm: WaterMark): String =
        "text='${wm.text}' size=${wm.textSize} degree=${wm.degree} tile=${wm.tileMode} " +
            "hGap=${wm.hGap} vGap=${wm.vGap} alpha=${wm.alpha} " +
            "color=0x${(wm.textColor.toLong() and 0xFFFFFFFFL).toString(16).uppercase()} " +
            "typeface=${wm.textTypeface::class.simpleName} style=${wm.textStyle::class.simpleName}"
}
