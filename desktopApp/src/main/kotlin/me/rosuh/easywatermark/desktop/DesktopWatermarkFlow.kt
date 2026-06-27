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
import me.rosuh.easywatermark.render.DesktopWatermarkComposer
import java.io.File

/**
 * S4d-121: the S4d-120 headless save spine extracted into reusable Desktop-app functions so BOTH the
 * `--headless` automation path ([runHeadless] in `Main.kt`) and the Compose Desktop window
 * ([launchDesktopWindow]) drive the **same** open → edit → render → save flow over the committed shared
 * APIs — the window does NOT fake a preview.
 *
 * S4d-122/123: this flow passes `WaterMark.textColor`, `textTypeface`, and `textStyle` into
 * [DesktopWatermarkComposer.composeOverRealImage]; all three are raster-honored on Desktop Skiko.
 *
 * S4d-128: the output **format + quality** are read from a provided [UserConfigRepository] (an empty
 * store yields the shared default `UserPreferences.DEFAULT == (JPEG, 80)` — matching Android, no
 * Desktop-only PNG default) and passed to the composer; the output filename extension follows the format.
 * Still out of scope: icon watermark.
 */
object DesktopWatermarkFlow {

    /** Repo-local watermark-config store dir (NOT `$HOME`) — shared with the S4d-120 flow. */
    val storeDir: File = File("build/s4d120-desktop-watermark-config")

    /** Repo-local **output-prefs** store dir — separate from the S4d-80 witness store, never written here
     *  (the flow only READS it), so an empty store keeps yielding the shared `(JPEG, 80)` default. */
    val userConfigStoreDir: File = File("build/s4d128-desktop-userconfig")

    /** Output dir (repo-local `build/`); the filename extension follows the chosen [ImageFormat]. */
    val outputDir: File = File("build/s4d120-desktop-headless")

    /** Default output file for [format]: JPEG → `.jpg`, PNG → `.png`. */
    fun defaultOutputFile(format: ImageFormat): File =
        File(outputDir, "watermarked." + if (format == ImageFormat.JPEG) "jpg" else "png")

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
     * Persist a config edit through [editor], re-read the persisted [WaterMark], read the output
     * [format]/[quality] from [userConfigRepo] (empty store → the shared `(JPEG, 80)` default), render the
     * [inputBytes] (or the deterministic 640×480 fixture) via [DesktopWatermarkComposer.composeOverRealImage]
     * in that format, and write to [outputFile] (default: [defaultOutputFile] for the chosen format, so the
     * extension matches). `suspend` (DataStore reads) — the caller picks the dispatcher (`runBlocking` for
     * headless; a UI coroutine for the window). The flow only READS the output prefs (never writes them).
     */
    suspend fun runSaveFlow(
        repo: WaterMarkRepository,
        editor: WatermarkConfigEditor,
        userConfigRepo: UserConfigRepository,
        inputBytes: ByteArray? = null,
        inputLabel: String = "<generated 640x480 fixture>",
        outputFile: File? = null,
    ): SaveOutcome {
        val bytes = inputBytes ?: DesktopWatermarkComposer.sampleBackgroundPng(width = 640, height = 480)
        val prefs = userConfigRepo.userPreferences.first() // empty store -> (JPEG, 80) (shared default)
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
            // Drive the persisted text color / typeface / paint style.
            colorArgb = wm.textColor,
            typeface = wm.textTypeface,
            textStyle = wm.textStyle,
            // S4d-128: output format + quality from the persisted user prefs (default JPEG/80).
            format = prefs.outputFormat,
            quality = prefs.compressLevel,
        )
        val target = outputFile ?: defaultOutputFile(prefs.outputFormat)
        target.parentFile?.mkdirs()
        target.writeBytes(result.png)
        return SaveOutcome(
            configInitial = describe(initial),
            configAfterEdit = describe(wm),
            inputLabel = inputLabel,
            inputByteCount = bytes.size,
            outputPath = target.path,
            format = prefs.outputFormat,
            width = result.width,
            height = result.height,
            outputByteCount = result.png.size,
        )
    }

    private fun describe(wm: WaterMark): String =
        "text='${wm.text}' size=${wm.textSize} degree=${wm.degree} tile=${wm.tileMode} " +
            "hGap=${wm.hGap} vGap=${wm.vGap} alpha=${wm.alpha} " +
            "color=0x${(wm.textColor.toLong() and 0xFFFFFFFFL).toString(16).uppercase()} " +
            "typeface=${wm.textTypeface::class.simpleName} style=${wm.textStyle::class.simpleName}"
}
