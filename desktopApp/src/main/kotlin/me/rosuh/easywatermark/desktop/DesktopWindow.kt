package me.rosuh.easywatermark.desktop

import androidx.compose.foundation.Image
import androidx.compose.foundation.draganddrop.dragAndDropTarget
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draganddrop.DragAndDropEvent
import androidx.compose.ui.draganddrop.DragAndDropTarget
import androidx.compose.ui.draganddrop.awtTransferable
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.painter.ColorPainter
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.rosuh.easywatermark.ProductVersion
import me.rosuh.easywatermark.data.db.buildTemplateDatabase
import me.rosuh.easywatermark.data.db.unpackDefaultTemplateSeed
import me.rosuh.easywatermark.data.model.FuncType
import me.rosuh.easywatermark.data.model.ImageFormat
import me.rosuh.easywatermark.data.model.ImageInfo
import me.rosuh.easywatermark.data.model.JobState
import me.rosuh.easywatermark.data.model.MediaRef
import me.rosuh.easywatermark.data.model.TextPaintStyle
import me.rosuh.easywatermark.data.model.TextTypeface
import me.rosuh.easywatermark.data.model.UserPreferences
import me.rosuh.easywatermark.data.model.WaterMark
import me.rosuh.easywatermark.data.model.WatermarkConfigChange
import me.rosuh.easywatermark.data.repo.DesktopIconPersistence
import me.rosuh.easywatermark.data.repo.TemplateRepository
import me.rosuh.easywatermark.domain.OutputPrefsEditor
import me.rosuh.easywatermark.domain.TemplateEditor
import me.rosuh.easywatermark.domain.WatermarkConfigEditor
import me.rosuh.easywatermark.render.DesktopImageDecoder
import me.rosuh.easywatermark.render.DesktopSaveDecision
import me.rosuh.easywatermark.session.AppIntent
import me.rosuh.easywatermark.session.DesktopExportPipelinePort
import me.rosuh.easywatermark.session.WatermarkSessionViewModel
import me.rosuh.easywatermark.shared.generated.resources.Res
import me.rosuh.easywatermark.shared.generated.resources.dialog_export_to_gallery
import me.rosuh.easywatermark.shared.generated.resources.dialog_save_exporting
import me.rosuh.easywatermark.shared.generated.resources.share
import me.rosuh.easywatermark.ui.EditorBottomControls
import me.rosuh.easywatermark.ui.EditorScreen
import me.rosuh.easywatermark.shared.generated.resources.dev_comment
import me.rosuh.easywatermark.ui.about.AboutDevCard
import me.rosuh.easywatermark.ui.about.AboutScreenIcons
import me.rosuh.easywatermark.ui.about.AboutScreen
import me.rosuh.easywatermark.ui.about.OpenSourceScreen
import me.rosuh.easywatermark.ui.EditorOptionItem
import me.rosuh.easywatermark.ui.EditorTemplateSheetHost
import me.rosuh.easywatermark.ui.Image as GalleryImage
import me.rosuh.easywatermark.ui.label
import me.rosuh.easywatermark.ui.iconPainter
import me.rosuh.easywatermark.ui.ProductShellHost
import me.rosuh.easywatermark.ui.ProductShellNav
import me.rosuh.easywatermark.ui.SharedProductDrawables

import me.rosuh.easywatermark.ui.compose.IconWatermarkOption
import me.rosuh.easywatermark.ui.compose.TextColorOption
import me.rosuh.easywatermark.ui.compose.TextPaintStyleLabels
import me.rosuh.easywatermark.ui.compose.TextPaintStyleOption
import me.rosuh.easywatermark.ui.compose.WatermarkModeActions
import me.rosuh.easywatermark.ui.compose.WatermarkModeActionsLabels
import me.rosuh.easywatermark.ui.compose.formatArgbHexColor
import me.rosuh.easywatermark.ui.compose.parseArgbHexColor
import me.rosuh.easywatermark.ui.save.SavedOutputActions
import me.rosuh.easywatermark.ui.save.SavedOutputActionsLabels
import me.rosuh.easywatermark.ui.save.SaveCommandActions
import me.rosuh.easywatermark.ui.save.SaveCommandActionsLabels
import me.rosuh.easywatermark.ui.save.SaveExportOptionsSection
import me.rosuh.easywatermark.ui.save.SaveExportSheetShell

import me.rosuh.easywatermark.ui.theme.AppTheme
import org.jetbrains.compose.resources.stringResource
import java.awt.Desktop
import java.awt.FileDialog
import java.awt.datatransfer.DataFlavor
import java.io.File
import java.net.URI
import java.util.prefs.Preferences

/** Best-effort Open-dialog filename filter (honored on macOS; ignored on some platforms — harmless). */
private val IMAGE_EXTENSIONS = setOf("png", "jpg", "jpeg", "webp", "bmp", "gif")

// About link edges (match Android ComposeMainActivity / iOS IosProductRootHost).
private const val ABOUT_URL_RELEASES = "https://github.com/rosuH/EasyWatermark/releases/"
private const val ABOUT_URL_ISSUES = "https://github.com/rosuH/EasyWatermark/issues/new"
private const val ABOUT_URL_PRIVACY_ZH =
    "https://github.com/rosuH/EasyWatermark/blob/master/PrivacyPolicy_zh-CN.md"
private const val ABOUT_URL_PRIVACY_EN =
    "https://github.com/rosuH/EasyWatermark/blob/master/PrivacyPolicy.md"
private const val ABOUT_URL_DEV = "https://github.com/rosuH"
private const val ABOUT_URL_DESIGNER = "https://tovi.fun/"
private const val ABOUT_URL_RATE =
    "https://github.com/rosuH/EasyWatermark#readme"

/** Short label for the current output preference, e.g. "JPEG / 80". */
private fun describePref(p: UserPreferences): String = "${p.outputFormat} / ${p.compressLevel}"

/** Open a URL in the system browser (About rows). Soft-fail → status string. */
private fun openUrlInBrowser(url: String): String? {
    return try {
        if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
            Desktop.getDesktop().browse(URI(url))
            null
        } else {
            "Open URL not supported on this Desktop"
        }
    } catch (t: Throwable) {
        "Open URL failed: ${t.message}"
    }
}

/** Sticky "Force Dynamic Color" flag (Android CMonet parity; no Material You on Desktop). */
private object DesktopDynamicColorPrefs {
    private const val KEY = "force_dynamic_color"
    private val prefs: Preferences =
        Preferences.userRoot().node("me/rosuh/easywatermark/desktop")

    fun isForced(): Boolean = prefs.getBoolean(KEY, false)
    fun setForced(enabled: Boolean) {
        prefs.putBoolean(KEY, enabled)
    }
}

/**
 * / : drop-target file extraction. [hasFileList] is the cheap drag-over predicate (flavor
 * Only); [supportedImageFiles] does the real extraction on drop — reads the dropped file list and returns * ALL files whose extension is in [IMAGE_EXTENSIONS] (order preserved), via the pure, unit-tested
 * [DesktopSaveDecision.supportedImageFiles]. Both swallow AWT failures → false/empty (soft-fail, never
 * crash). The AWT file-list flavor is the desktop drag-drop interop; commonMain is untouched.
 */
@OptIn(ExperimentalComposeUiApi::class)
private fun hasFileList(event: DragAndDropEvent): Boolean = try {
    event.awtTransferable.isDataFlavorSupported(DataFlavor.javaFileListFlavor)
} catch (t: Throwable) {
    false
}

@OptIn(ExperimentalComposeUiApi::class)
private fun supportedImageFiles(event: DragAndDropEvent): List<File> = try {
    val transferable = event.awtTransferable
    if (transferable.isDataFlavorSupported(DataFlavor.javaFileListFlavor)) {
        @Suppress("UNCHECKED_CAST")
        val files = (transferable.getTransferData(DataFlavor.javaFileListFlavor) as? List<File>).orEmpty()
        DesktopSaveDecision.supportedImageFiles(files, IMAGE_EXTENSIONS)
    } else {
        emptyList()
    }
} catch (t: Throwable) {
    emptyList()
}

/** Stable UI label for a [TextTypeface] — explicit map (no reflection / object-name dependency). */
private fun typefaceLabelOf(t: TextTypeface): String = when (t) {
    TextTypeface.Normal -> "Normal"
    TextTypeface.Italic -> "Italic"
    TextTypeface.Bold -> "Bold"
    TextTypeface.BoldItalic -> "BoldItalic"
}

/** Stable UI label for a [TextPaintStyle] — explicit map (no reflection / object-name dependency). */
private fun styleLabelOf(s: TextPaintStyle): String = when (s) {
    TextPaintStyle.Fill -> "Fill"
    TextPaintStyle.Stroke -> "Stroke"
}

private class LastImage(val bytes: ByteArray, val label: String)

/**
 * The stable per-user app-data dir the interactive Desktop window persists into — * `~/.easywatermark` (matching the `CreateDataStore.desktop.kt` store-creation convention), NOT the
 * repo-local `build/` dev paths the headless/demo witness (`Main.kt`) deliberately uses. If
 * `user.home` is unavailable, fall back to a repo-local `build/` dir and warn instead of crashing.
 * The dir is created if missing.
 */
private fun resolveDesktopAppDataDir(): File {
    val home = System.getProperty("user.home")?.takeIf { it.isNotBlank() }
    return if (home != null) {
        File(home, ".easywatermark")
    } else {
        System.err.println(
            "S4d-215: user.home unavailable; persisting window state under repo-local build/desktop-app-data"
        )
        File("build/desktop-app-data")
    }.apply { mkdirs() }
}

/**
 * The user-facing output dir for the interactive window's REAL saves (drop / Render & Save / * Open image) — `~/Pictures` when it exists, else `~/.easywatermark/output` (reusing the
 * app-data dir). Saved watermarked images must not land in the repo-local `build/` dir. The
 * headless/demo witness (`Main.kt`) and the `DesktopWatermarkFlow` default `outputDir` stay build-local.
 * The interactive preview temp stays app-private so the packaged `.app` does not depend on its launch
 * working directory. (Full per-OS XDG/AppData handling is a separate deferred refinement.) The dir is
 * created if missing.
 */
private fun resolveDesktopOutputDir(): File {
    val pictures = System.getProperty("user.home")?.takeIf { it.isNotBlank() }?.let { File(it, "Pictures") }
    return (pictures?.takeIf { it.isDirectory } ?: File(resolveDesktopAppDataDir(), "output"))
        .apply { mkdirs() }
}


// Product routes + transitions: shared [ProductShellNav] / [ProductShellHost].

@Composable
private fun desktopOptionLabel(type: FuncType): String = type.label()

@Composable
private fun desktopOptionIcon(type: FuncType): Painter = type.iconPainter()


/**
 * Compose Desktop product window.
 *
 * Open/Drop: session export with unique outputs. Preview / Save As / sample:
 * [DesktopWatermarkFlow.runSaveFlow] → [DesktopRenderSaveSpine]. Preview uses a private temp only.
 */
fun launchDesktopWindow() = application {
    // persist the window's user state (watermark config, output prefs, templates DB) under the
    // stable per-user app-data dir ~/.easywatermark (the CreateDataStore.desktop.kt convention), NOT the
    // repo-local build/ dev paths — those stay the intentional headless/demo witness layout (Main.kt +
    // DesktopWatermarkFlow defaults). All three persistence files (the two DataStores named by their
    // SP_NAME + the Room "ewm-db") share this dir; distinct filenames, no collision.
    val appDataDir = remember { resolveDesktopAppDataDir() }
    // where the window's REAL saves (drop / Render & Save / Open image) write — a user dir, not
    // the repo-local build/ default. "Save as…" still uses its chosen path; the preview temp + headless
    // witness stay build-local.
    val outputDir = remember { resolveDesktopOutputDir() }
    // ONE repository + editor for the window's lifetime (DataStore forbids a second active store per file).
    val repo = remember { DesktopWatermarkFlow.buildRepository(dir = appDataDir) }
    val editor = remember { WatermarkConfigEditor(repo) }
    // the output-prefs repo the save flow reads (empty store → the shared (JPEG, 80) default).
    val userConfigRepo = remember { DesktopWatermarkFlow.buildUserConfigRepository(dir = appDataDir) }
    // ADR-0017 Phase 3: shared session VM + Desktop export port (Skiko spine). Open-image / drop batches
    // use session.exportAndAwait; Preview / Save-as / fixture sample keep runSaveFlow (in-memory bytes).
    val session = remember {
        WatermarkSessionViewModel(
            waterMarkRepo = repo,
            userConfigRepo = userConfigRepo,
            exportPipeline = DesktopExportPipelinePort(outputDirProvider = { outputDir }),
        )
    }
    val exportJobState by session.exportJobState.collectAsState()
    // the shared output-prefs write use-case over the SAME store the save flow reads.
    val outputEditor = remember { OutputPrefsEditor(userConfigRepo) }
    // ///: the Desktop templates Room DB (commonMain Room via the desktopMain
    // BundledSQLiteDriver builder), now under the stable app-data dir and seeded from the shared desktopMain
    // seed resource on first creation (Chinese for `zh` locales, English otherwise). Room is
    // single-instance-per-file; the process exits on window close, releasing the DB.
    val templateDb = remember {
        val seedFile = File(appDataDir, "seed-ewm-db-default.db").also { unpackDefaultTemplateSeed(it) }
        buildTemplateDatabase(appDataDir, seedFile)
    }
    val templateRepo = remember { TemplateRepository(templateDb.templateDao(), Dispatchers.IO) }
    val templateEditor = remember { TemplateEditor(templateRepo) }
    // Collect the saved templates into state (remember the Flow so collection is stable across recompositions).
    val templates by remember { templateRepo.getAllTemplate() }.collectAsState(emptyList())
    val scope = rememberCoroutineScope()
    var status by remember {
        mutableStateOf("Ready. Open/drop images use shared session export; sample/preview use runSaveFlow.")
    }
    // Surface shared export progress when a batch is running (Open image / drop).
    LaunchedEffect(exportJobState.isSaving, exportJobState.completedCount, exportJobState.totalCount) {
        if (exportJobState.isSaving && exportJobState.totalCount > 0) {
            status = "Session export ${exportJobState.completedCount}/${exportJobState.totalCount}…"
        }
    }
    var busy by remember { mutableStateOf(false) }
    var lastImage by remember { mutableStateOf<LastImage?>(null) }
    // the last REAL saved output file (set only by the Render & Save / Save as… / Open image…
    // success paths — NOT Preview, which writes a temp file). Drives the share-substitute buttons.
    var lastSavedFile by remember { mutableStateOf<File?>(null) }
    // packaged Desktop launches do not have the repository as their working directory. Keep the
    // interactive preview temp beside the existing per-user config/DB state instead of under `build/`.
    val previewFile = remember { File(appDataDir, "preview/preview.img").apply { parentFile?.mkdirs() } }
    // / C2: output prefs drive the shared SaveExportSheetShell (Android Compose export panel).
    var outputFormat by remember { mutableStateOf(ImageFormat.JPEG) }
    var outputQuality by remember { mutableStateOf(80) }
    // C2: product export chrome = shared SaveExportSheetShell; FS write/share stay platform edges.
    var showSaveSheet by remember { mutableStateOf(false) }
    // About open-source overlay (shared OpenSourceScreen; same as Android/iOS).
    var showOpenSource by remember { mutableStateOf(false) }
    var dynamicColorForced by remember { mutableStateOf(DesktopDynamicColorPrefs.isForced()) }
    /**
 * Filmstrip + export-sheet thumbs. Full [DesktopImageDecoder.decode] of multi-megapixel
 * Files on the UI thread freezes ModalBottomSheet open — always use [decodeThumbnail] off-EDT.     */
    val desktopThumbCache = remember { mutableMapOf<String, ImageBitmap>() }
    var desktopThumbEpoch by remember { mutableStateOf(0) }
    // U2: watermark config is session/repo-owned — collect once; no parallel mutableStateOf mirrors.
    val waterMark by repo.waterMark.collectAsState(WaterMark.default)
    // the rendered preview image (null until the first successful refresh).
    var preview by remember { mutableStateOf<ImageBitmap?>(null) }
    // Debounced preview refresh generation (slider ticks apply config immediately; raster is debounced).
    var previewGeneration by remember { mutableStateOf(0) }
    // Shared product shell routes (same transitions as Android/iOS). FileDialog stays Desktop edge.
    var productRoute by remember { mutableStateOf(ProductShellNav.Route.Launch) }
    var aboutReturnRoute by remember { mutableStateOf(ProductShellNav.Route.Launch) }
    val launchUi by session.launchScreenUiStateFlow.collectAsState()
    val sessionImages = launchUi.selectedImageList
    var selectedSessionImage by remember { mutableStateOf<ImageInfo?>(null) }
    LaunchedEffect(Unit) {
        userConfigRepo.userPreferences.first().let {
            outputFormat = it.outputFormat
            outputQuality = it.compressLevel
        }
    }

    // reactive preview. Render the CURRENT persisted config over the remembered image (or the
    // deterministic fixture) through the SAME DesktopWatermarkFlow.runSaveFlow spine the manual "Preview"
    // button uses, decode the bytes (DesktopImageDecoder, generic JPEG/PNG), and update the on-screen
    // `preview`. Writes ONLY the app-private temp preview path and never sets `lastSavedFile` — a preview
    // is NOT a real save (the share-substitute buttons stay bound to real saves). Keeps the last good preview
    // on failure (only replaces it on a successful decode). Heavy render+decode runs off the EDT inside
    // withContext(IO); the Compose `preview` state is set after, on the caller's UI dispatcher. Returns a
    // short status line. Callers invoke this inside their own `busy = true … busy = false` span (after a
    // successful explicit edit/mode/source change, or from the manual Preview button), so renders stay
    // serialized. Defined before the drop target so the drop refresh can call it.
    suspend fun refreshPreview(): String {
        val current = lastImage
        val (img, msg) = withContext(Dispatchers.IO) {
            try {
                val o = if (current != null) {
                    DesktopWatermarkFlow.runSaveFlow(
                        repo, editor, userConfigRepo,
                        inputBytes = current.bytes, inputLabel = current.label, outputFile = previewFile,
                    )
                } else {
                    DesktopWatermarkFlow.runSaveFlow(repo, editor, userConfigRepo, outputFile = previewFile)
                }
                DesktopImageDecoder.decode(previewFile.readBytes()) to
                    "Preview: ${o.format}, ${o.width}x${o.height} (${o.outputByteCount} B)"
            } catch (t: Throwable) {
                null to "Preview refresh failed (kept last preview): ${t.message}"
            }
        }
        // Only replace the visible preview on a successful decode (keep the last good one on failure).
        img?.let { preview = it }
        return msg
    }

    LaunchedEffect(previewGeneration) {
        if (previewGeneration == 0) return@LaunchedEffect
        delay(250)
        status = refreshPreview()
    }

    /** Shared system-pick batch spine (Launch CTA + Open image… + multi-select). */
    fun openImageFilesBatch(files: List<File>) {
        if (files.isEmpty()) return
        scope.launch {
            busy = true
            status = "Rendering ${files.size} image(s)…"
            var lastPicked: LastImage? = null
            var lastSaved: File? = null
            try {
                val next = withContext(Dispatchers.IO) {
                    try {
                        val infos = files.map { ImageInfo(MediaRef(it.absolutePath)) }
                        val gallery = files.mapIndexed { i, f ->
                            GalleryImage(
                                id = i,
                                uri = MediaRef(f.absolutePath),
                                name = f.name,
                                size = f.length(),
                                date = f.lastModified(),
                                check = true,
                            )
                        }
                        session.dispatchAndAwait(
                            AppIntent.EnterEditor(
                                selected = infos,
                                gallerySnapshot = gallery,
                                waterMark = repo.waterMark.first(),
                            ),
                        )
                        session.exportAndAwait(infos)
                        var successCount = 0
                        var failCount = 0
                        var firstFailure: String? = null
                        for ((file, info) in files.zip(infos)) {
                            when (val st = info.jobState) {
                                is JobState.Success -> {
                                    successCount++
                                    val outPath = (info.result?.data as? MediaRef)?.value
                                    if (outPath != null) {
                                        lastSaved = File(outPath)
                                        lastPicked = LastImage(file.readBytes(), file.path)
                                    }
                                }
                                is JobState.Failure -> {
                                    failCount++
                                    if (firstFailure == null) {
                                        firstFailure = "${file.name}: ${st.result.message ?: st.result.code}"
                                    }
                                }
                                else -> {
                                    failCount++
                                    if (firstFailure == null) firstFailure = "${file.name}: incomplete"
                                }
                            }
                        }
                        val exp = session.exportJobState.value
                        buildString {
                            append(
                                "Saved $successCount/${files.size} images to ${outputDir.path} " +
                                    "(session export ${exp.completedCount}/${exp.totalCount})",
                            )
                            if (failCount > 0) append(" · $failCount failed: $firstFailure")
                        }
                    } catch (t: Throwable) {
                        "Failed: ${t.message}"
                    }
                }
                lastPicked?.let { lastImage = it }
                lastSaved?.let { lastSavedFile = it }
                if (lastPicked != null) {
                    productRoute = ProductShellNav.Route.Editor
                    selectedSessionImage = session.launchScreenUiStateFlow.value.curImageInfo
                        ?: sessionImages.firstOrNull()
                }
                status = if (lastSaved != null) "$next · ${refreshPreview()}" else next
            } finally {
                busy = false
            }
        }
    }

    // drop image file(s) onto the window to load them through the SAME save spine as "Open image…".
    // a multi-file drop now watermarks and saves EVERY supported dropped image (was first-only),
    // sequentially, to the user output dir with collision-free names. onDrop runs on the Compose UI thread,
    // so it reads/sets state directly and launches the heavy render loop on `scope`. A drop while busy or a
    // drop with no supported image fails softly with a status (no crash). Remembered so the target identity
    // is stable.
    val dropTarget = remember {
        object : DragAndDropTarget {
            override fun onDrop(event: DragAndDropEvent): Boolean {
                if (busy) {
                    status = "Busy — wait for the current render before dropping another image."
                    return false
                }
                // take ALL supported dropped images (pure DesktopSaveDecision.supportedImageFiles).
                val files = supportedImageFiles(event)
                if (files.isEmpty()) {
                    status = "Unsupported drop — no supported image files in drop (${IMAGE_EXTENSIONS.joinToString(", ")})."
                    return false
                }
                scope.launch {
                    busy = true
                    status = "Rendering ${files.size} image(s)…"
                    // Remember the LAST successful image/output (for reuse + the share-substitute buttons).
                    var lastPicked: LastImage? = null
                    var lastSaved: File? = null
                    // the whole batch span is wrapped in try/finally so `busy` is ALWAYS reset —
                    // even if setup (notably reading the output prefs) throws BEFORE the per-file loop. This
                    // restores the old single-file drop's recovery: a setup failure must not leave the UI stuck.
                    try {
                        // Phase 3: same shared session export path as Open image… (DesktopExportPipelinePort).
                        val next = withContext(Dispatchers.IO) {
                            try {
                                val infos = files.map { ImageInfo(MediaRef(it.absolutePath)) }
                                val gallery = files.mapIndexed { i, f ->
                                    GalleryImage(
                                        id = i,
                                        uri = MediaRef(f.absolutePath),
                                        name = f.name,
                                        size = f.length(),
                                        date = f.lastModified(),
                                        check = true,
                                    )
                                }
                                session.dispatchAndAwait(
                                    AppIntent.EnterEditor(
                                        selected = infos,
                                        gallerySnapshot = gallery,
                                        waterMark = repo.waterMark.first(),
                                    ),
                                )
                                session.exportAndAwait(infos)
                                var successCount = 0
                                var failCount = 0
                                var firstFailure: String? = null
                                for ((file, info) in files.zip(infos)) {
                                    when (val st = info.jobState) {
                                        is JobState.Success -> {
                                            successCount++
                                            val outPath = (info.result?.data as? MediaRef)?.value
                                            if (outPath != null) {
                                                lastSaved = File(outPath)
                                                lastPicked = LastImage(file.readBytes(), file.path)
                                            }
                                        }
                                        is JobState.Failure -> {
                                            failCount++
                                            if (firstFailure == null) {
                                                firstFailure = "${file.name}: ${st.result.message ?: st.result.code}"
                                            }
                                        }
                                        else -> {
                                            failCount++
                                            if (firstFailure == null) firstFailure = "${file.name}: incomplete"
                                        }
                                    }
                                }
                                val exp = session.exportJobState.value
                                buildString {
                                    append(
                                        "Saved $successCount/${files.size} images to ${outputDir.path} " +
                                            "(session export ${exp.completedCount}/${exp.totalCount})",
                                    )
                                    if (failCount > 0) append(" · $failCount failed: $firstFailure")
                                }
                            } catch (t: Throwable) {
                                "Failed: ${t.message}"
                            }
                        }
                        lastPicked?.let { lastImage = it }
                        lastSaved?.let { lastSavedFile = it }
                        if (lastPicked != null) productRoute = ProductShellNav.Route.Editor
                        // refresh the preview AT MOST ONCE after the batch, only when ≥1 save succeeded
                        // (over the last successful image). refreshPreview writes ONLY the temp preview file
                        // (never lastSavedFile, so the share-substitute buttons stay bound to real saves).
                        status = if (lastSaved != null) "$next · ${refreshPreview()}" else next
                    } finally {
                        busy = false
                    }
                }
                return true
            }
        }
    }

    Window(onCloseRequest = ::exitApplication, title = "EasyWatermark — Desktop") {
        AppTheme(darkTheme = true) {
            val selectedForStrip = launchUi.curImageInfo
                ?: selectedSessionImage
                ?: sessionImages.firstOrNull()
            val aboutPainter = SharedProductDrawables.aboutPainter()
            val backPainter = SharedProductDrawables.backPainter()
            val addPainter = SharedProductDrawables.pickerImagePainter()
            val savePainter = SharedProductDrawables.savePainter()
            val versionPainter = SharedProductDrawables.versionPainter()
            val ratePainter = SharedProductDrawables.ratePainter()
            val feedbackPainter = SharedProductDrawables.feedbackPainter()
            val updateLogPainter = SharedProductDrawables.updateLogPainter()
            val openSourcePainter = SharedProductDrawables.openSourcePainter()
            val privacyZhPainter = SharedProductDrawables.privacyZhPainter()
            val privacyEnPainter = SharedProductDrawables.privacyEnPainter()
            val logoToolbarPainter = SharedProductDrawables.logoToolbarPainter()
            val templateListPainter = SharedProductDrawables.templateListPainter()
            val avatarDevPainter = SharedProductDrawables.avatarDevPainter()
            val avatarToviPainter = SharedProductDrawables.avatarToviPainter()

            val editorModifier = if (productRoute == ProductShellNav.Route.Editor) {
                Modifier.fillMaxSize()
                    .dragAndDropTarget(shouldStartDragAndDrop = { hasFileList(it) }, target = dropTarget)
            } else {
                Modifier.fillMaxSize()
            }
            ProductShellHost(route = productRoute) { route ->
            when (route) {
                ProductShellNav.Route.Launch -> {
                    me.rosuh.easywatermark.ui.LaunchScreen(
                        aboutIcon = aboutPainter,
                        onPickImage = {
                            val dialog = FileDialog(window, "Open image", FileDialog.LOAD).apply {
                                isMultipleMode = true
                                setFilenameFilter { _, fileName ->
                                    fileName.substringAfterLast('.', "").lowercase() in IMAGE_EXTENSIONS
                                }
                                isVisible = true
                            }
                            val files = DesktopSaveDecision.supportedImageFiles(dialog.files.toList(), IMAGE_EXTENSIONS)
                            if (files.isNotEmpty()) {
                                productRoute = ProductShellNav.Route.Editor
                                openImageFilesBatch(files)
                            }
                        },
                        onGoAbout = {
                            val (about, ret) = ProductShellNav.openAbout(ProductShellNav.Route.Launch)
                            aboutReturnRoute = ret
                            productRoute = about
                        },
                        logo = { modifier, shouldAnimate ->
                            me.rosuh.easywatermark.ui.BrandLogo(
                                modifier = modifier,
                                animate = shouldAnimate,
                            )
                        },
                        modifier = editorModifier,
                    )
                }
                ProductShellNav.Route.About -> {
                    // UI is shared AboutScreen; Desktop only wires system edges (browser / prefs).
                    val aboutShowBounds = waterMark.enableBounds
                    val devComment = stringResource(Res.string.dev_comment)
                    AboutScreen(
                        versionName = ProductVersion.NAME,
                        showBounds = aboutShowBounds,
                        dynamicColorOn = dynamicColorForced,
                        icons = AboutScreenIcons(
                            back = backPainter,
                            version = versionPainter,
                            rating = ratePainter,
                            feedback = feedbackPainter,
                            updateLog = updateLogPainter,
                            openSource = openSourcePainter,
                            privacyZh = privacyZhPainter,
                            privacyEn = privacyEnPainter,
                        ),
                        developerCard = AboutDevCard(
                            title = "Developed with ♥ by rosu",
                            description = devComment,
                            avatar = avatarDevPainter,
                        ),
                        designerCard = AboutDevCard(
                            title = "Designed with ♥ by tovi",
                            description = "A Designer.",
                            avatar = avatarToviPainter,
                        ),
                        onBack = {
                            showOpenSource = false
                            productRoute = ProductShellNav.aboutBack(aboutReturnRoute)
                        },
                        onVersion = {
                            openUrlInBrowser(ABOUT_URL_RELEASES)?.let { status = it }
                        },
                        onRate = {
                            openUrlInBrowser(ABOUT_URL_RATE)?.let { status = it }
                        },
                        onFeedback = {
                            openUrlInBrowser(ABOUT_URL_ISSUES)?.let { status = it }
                        },
                        onUpdateLog = {
                            openUrlInBrowser(ABOUT_URL_RELEASES)?.let { status = it }
                        },
                        onOpenSource = { showOpenSource = true },
                        onPrivacyZh = {
                            openUrlInBrowser(ABOUT_URL_PRIVACY_ZH)?.let { status = it }
                        },
                        onPrivacyEn = {
                            openUrlInBrowser(ABOUT_URL_PRIVACY_EN)?.let { status = it }
                        },
                        onDeveloper = {
                            openUrlInBrowser(ABOUT_URL_DEV)?.let { status = it }
                        },
                        onDesigner = {
                            openUrlInBrowser(ABOUT_URL_DESIGNER)?.let { status = it }
                        },
                        onToggleBounds = { enabled ->
                            scope.launch {
                                repo.toggleBounds(enabled)
                            }
                        },
                        onToggleDynamicColor = { enabled ->
                            DesktopDynamicColorPrefs.setForced(enabled)
                            dynamicColorForced = enabled
                            status = if (enabled) {
                                "Dynamic color flag on (Android Material You)"
                            } else {
                                "Dynamic color flag off"
                            }
                        },
                        logo = { modifier ->
                            me.rosuh.easywatermark.ui.AboutPageLogo(
                                modifier = modifier,
                                animate = true,
                            )
                        },
                        modifier = editorModifier,
                    )
                }
                ProductShellNav.Route.Editor -> {
                    var colorDraft by remember(waterMark.textColor) {
                        mutableStateOf(formatArgbHexColor(waterMark.textColor))
                    }
                    me.rosuh.easywatermark.ui.EditorScreen(
                        imageList = sessionImages,
                        waterMark = waterMark,
                        selectedImage = selectedForStrip,
                        templates = templates,
                        icons = me.rosuh.easywatermark.ui.EditorUiIcons(
                            back = backPainter,
                            addMoreImages = addPainter,
                            save = savePainter,
                            about = aboutPainter,
                            templateList = templateListPainter,
                        ),
                        preview = { previewModifier ->
                            // Product editor: no debug "Preview: JPEG, WxH" chrome; fill the
                            // available frame with ContentScale.Fit (responsive, aspect preserved).
                            Box(
                                modifier = previewModifier.fillMaxSize(),
                                contentAlignment = Alignment.Center,
                            ) {
                                val bmp = preview
                                if (bmp != null) {
                                    Image(
                                        bitmap = bmp,
                                        contentDescription = "Watermark preview",
                                        contentScale = ContentScale.Fit,
                                        modifier = Modifier.fillMaxSize().padding(12.dp),
                                    )
                                } else {
                                    Text(
                                        text = status.ifBlank { "No image" },
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        },
                        thumbnail = { imageInfo, contentDescription, thumbModifier ->
                            val path = imageInfo.uri.value
                            val epoch = desktopThumbEpoch
                            val cached = desktopThumbCache[path]
                            val bmp by produceState(initialValue = cached, path, epoch) {
                                if (path.isBlank()) {
                                    value = null
                                    return@produceState
                                }
                                desktopThumbCache[path]?.let {
                                    value = it
                                    return@produceState
                                }
                                value = withContext(Dispatchers.IO) {
                                    runCatching {
                                        DesktopImageDecoder.decodeThumbnail(File(path), maxEdgePx = 96)
                                    }.getOrNull()
                                }?.also { desktopThumbCache[path] = it }
                            }
                            if (bmp != null) {
                                Image(
                                    bitmap = bmp!!,
                                    contentDescription = contentDescription,
                                    contentScale = ContentScale.Crop,
                                    modifier = thumbModifier,
                                )
                            } else {
                                Box(
                                    modifier = thumbModifier.background(MaterialTheme.colorScheme.surfaceVariant),
                                )
                            }
                        },
                        optionItem = { spec, selected ->
                            val label = desktopOptionLabel(spec.type)
                            EditorOptionItem(
                                icon = desktopOptionIcon(spec.type),
                                contentDescription = label,
                                label = label,
                                selected = selected,
                            )
                        },
                        colorOption = { optionModifier, mark, onColor ->
                            TextColorOption(
                                currentColor = mark.textColor,
                                customText = colorDraft,
                                enabled = !busy,
                                modifier = optionModifier,
                                showCustomPicker = true,
                                showCustomInput = false,
                                onColorSelected = onColor,
                                onCustomTextChange = { colorDraft = it },
                            )
                        },
                        iconOption = { optionModifier, mark, onIcon ->
                            IconWatermarkOption(
                                hasIcon = mark.iconUri.isEmpty().not(),
                                pickLabel = "Open icon…",
                                modifier = optionModifier,
                                enabled = !busy,
                                onPick = {
                                    val dialog = FileDialog(window, "Open icon", FileDialog.LOAD).apply {
                                        setFilenameFilter { _, fileName ->
                                            fileName.substringAfterLast('.', "").lowercase() in IMAGE_EXTENSIONS
                                        }
                                        isVisible = true
                                    }
                                    val dir = dialog.directory
                                    val name = dialog.file
                                    if (dir != null && name != null) {
                                        val selected = File(dir, name)
                                        scope.launch {
                                            try {
                                                val copied = withContext(Dispatchers.IO) {
                                                    DesktopIconPersistence.persistIcon(
                                                        selected, File(appDataDir, "watermark_icons"),
                                                    )
                                                }
                                                onIcon(MediaRef(copied.absolutePath))
                                            } catch (t: Throwable) {
                                                status = "Failed: ${t.message}"
                                            }
                                        }
                                    }
                                },
                                preview = {},
                            )
                        },
                        onBack = { productRoute = ProductShellNav.Route.Launch },
                        onAddMoreImages = {
                            val dialog = FileDialog(window, "Open image", FileDialog.LOAD).apply {
                                isMultipleMode = true
                                setFilenameFilter { _, fileName ->
                                    fileName.substringAfterLast('.', "").lowercase() in IMAGE_EXTENSIONS
                                }
                                isVisible = true
                            }
                            val files = DesktopSaveDecision.supportedImageFiles(dialog.files.toList(), IMAGE_EXTENSIONS)
                            if (files.isNotEmpty()) openImageFilesBatch(files)
                        },
                        onShowSaveDialog = {
                            // Open Android-parity export panel; do not write files until primary CTA.
                            session.resetJobStatus()
                            showSaveSheet = true
                        },
                        onGoAboutScreen = {
                            val (about, ret) = ProductShellNav.openAbout(ProductShellNav.Route.Editor)
                            aboutReturnRoute = ret
                            productRoute = about
                        },
                        onImageSelected = { info ->
                            selectedSessionImage = info
                            session.selectImage(info.uri)
                            val path = info.uri.value
                            val file = java.io.File(path)
                            if (file.isFile) {
                                scope.launch {
                                    lastImage = LastImage(file.readBytes(), file.path)
                                    previewGeneration++
                                }
                            }
                        },
                        onConfigChange = { type, value ->
                            if (!busy) {
                                scope.launch {
                                    busy = true
                                    val (msg, ok) = withContext(Dispatchers.IO) {
                                        try {
                                            session.dispatchAndAwait(
                                                AppIntent.ApplyConfig(WatermarkConfigChange.from(type, value)),
                                            )
                                            "Applied $type" to true
                                        } catch (t: Throwable) {
                                            "Failed: ${t.message}" to false
                                        }
                                    }
                                    if (ok) previewGeneration++
                                    status = msg
                                    busy = false
                                }
                            }
                        },
                        onUseTemplate = { template ->
                            val content = template.content
                            if (content != null) {
                                scope.launch {
                                    busy = true
                                    val (msg, ok) = withContext(Dispatchers.IO) {
                                        try {
                                            session.dispatchAndAwait(
                                                AppIntent.ApplyConfig(WatermarkConfigChange.Text(content)),
                                            )
                                            "Template applied" to true
                                        } catch (t: Throwable) {
                                            "Failed: ${t.message}" to false
                                        }
                                    }
                                    if (ok) previewGeneration++
                                    status = msg
                                    busy = false
                                }
                            }
                        },
                        onAddTemplate = { text ->
                            scope.launch {
                                busy = true
                                status = withContext(Dispatchers.IO) {
                                    try {
                                        templateEditor.add(text)
                                        "Saved template"
                                    } catch (t: Throwable) {
                                        "Failed: ${t.message}"
                                    }
                                }
                                busy = false
                            }
                        },
                        onUpdateTemplate = { template ->
                            scope.launch {
                                busy = true
                                status = withContext(Dispatchers.IO) {
                                    try {
                                        templateEditor.update(template)
                                        "Updated template"
                                    } catch (t: Throwable) {
                                        "Failed: ${t.message}"
                                    }
                                }
                                busy = false
                            }
                        },
                        onDeleteTemplate = { template ->
                            scope.launch {
                                busy = true
                                status = withContext(Dispatchers.IO) {
                                    try {
                                        templateEditor.delete(template)
                                        "Template deleted"
                                    } catch (t: Throwable) {
                                        "Failed: ${t.message}"
                                    }
                                }
                                busy = false
                            }
                        },
                        modifier = editorModifier,
                    )
                }
            }
            } // ProductShellHost

            if (showOpenSource) {
                OpenSourceScreen(
                    onBack = { showOpenSource = false },
                    onOpenLink = { url ->
                        openUrlInBrowser(url)?.let { status = it }
                    },
                    backIcon = backPainter,
                    modifier = Modifier.fillMaxSize(),
                )
            }

            // C2: shared Android Compose export panel; Desktop only implements FS write + reveal/share edges.
            if (showSaveSheet) {
                val exportItems = sessionImages.ifEmpty {
                    val path = lastImage?.label
                    if (path != null && File(path).isFile) {
                        listOf(ImageInfo(MediaRef(path)))
                    } else {
                        emptyList()
                    }
                }
                // Prefetch export thumbs off-EDT so sheet open is not blocked on full-res ImageIO.
                LaunchedEffect(exportItems.map { it.uri.value }) {
                    val missing = exportItems.map { it.uri.value }.filter {
                        it.isNotBlank() && !desktopThumbCache.containsKey(it)
                    }
                    if (missing.isEmpty()) return@LaunchedEffect
                    withContext(Dispatchers.IO) {
                        for (path in missing) {
                            runCatching {
                                DesktopImageDecoder.decodeThumbnail(File(path), maxEdgePx = 96)
                            }.getOrNull()?.let { desktopThumbCache[path] = it }
                        }
                    }
                    desktopThumbEpoch += 1
                }
                val exportTotal = exportJobState.totalCount.takeIf { it > 0 } ?: exportItems.size.coerceAtLeast(1)
                val primaryLabel = when {
                    exportJobState.isSaving -> stringResource(Res.string.dialog_save_exporting)
                    exportJobState.isFinished -> stringResource(Res.string.share)
                    else -> stringResource(Res.string.dialog_export_to_gallery)
                }
                val exportTotalFixed = exportItems.size.coerceAtLeast(if (lastImage != null) 1 else 0)
                val completedFixed = exportItems.count {
                    it.jobState is me.rosuh.easywatermark.data.model.JobState.Success
                }.coerceAtLeast(exportJobState.completedCount)
                SaveExportSheetShell(
                    items = exportItems,
                    selectedFormat = outputFormat,
                    quality = outputQuality,
                    primaryActionLabel = primaryLabel,
                    primaryActionEnabled = !exportJobState.isSaving && !busy,
                    showOpenGallery = exportJobState.isFinished && lastSavedFile != null,
                    exportListSubtitle = "$completedFixed/$exportTotalFixed",
                    imageCount = exportTotalFixed,
                    itemKey = { it.uri.value },
                    onDismiss = {
                        if (!exportJobState.isSaving) showSaveSheet = false
                    },
                    onFormatClick = { fmt ->
                        scope.launch {
                            outputEditor.save(fmt, outputQuality)
                            outputFormat = fmt
                        }
                    },
                    onQualityChange = { q ->
                        scope.launch {
                            outputEditor.save(outputFormat, q)
                            outputQuality = q
                        }
                    },
                    onExportClick = {
                        if (exportJobState.isFinished) {
                            // E09 share substitute: reveal folder of last real save.
                            val file = lastSavedFile
                            if (file != null && Desktop.isDesktopSupported()) {
                                try {
                                    Desktop.getDesktop().open(file.parentFile ?: file)
                                } catch (t: Throwable) {
                                    status = "Reveal failed: ${t.message}"
                                }
                            }
                        } else {
                            scope.launch {
                                busy = true
                                try {
                                    if (exportItems.isNotEmpty()) {
                                        withContext(Dispatchers.IO) {
                                            session.exportAndAwait(exportItems)
                                        }
                                        var last: File? = null
                                        for (info in exportItems) {
                                            val outPath = (info.result?.data as? MediaRef)?.value
                                            if (outPath != null) last = File(outPath)
                                        }
                                        last?.let { lastSavedFile = it }
                                        val exp = session.exportJobState.value
                                        status = "Exported ${exp.completedCount}/${exp.totalCount} → ${outputDir.path}"
                                    } else {
                                        // No session image: fixture sample via existing save spine (platform edge).
                                        val out = withContext(Dispatchers.IO) {
                                            val fmt = userConfigRepo.userPreferences.first().outputFormat
                                            val target = DesktopSaveDecision.resolveUniqueOutputFile(outputDir, fmt)
                                            val o = DesktopWatermarkFlow.runSaveFlow(
                                                repo, editor, userConfigRepo, outputFile = target,
                                            )
                                            File(o.outputPath)
                                        }
                                        lastSavedFile = out
                                        session.markExportFinished(completedCount = 1, totalCount = 1)
                                        status = "Saved: ${out.path}"
                                    }
                                } catch (t: Throwable) {
                                    status = "Export failed: ${t.message}"
                                } finally {
                                    busy = false
                                }
                            }
                        }
                    },
                    onOpenGalleryClick = {
                        // E10 "open gallery" substitute: reveal output directory.
                        val file = lastSavedFile
                        if (file != null && Desktop.isDesktopSupported()) {
                            try {
                                Desktop.getDesktop().open(file.parentFile ?: file)
                            } catch (t: Throwable) {
                                status = "Open folder failed: ${t.message}"
                            }
                        }
                    },
                ) { info, thumbModifier ->
                    val path = info.uri.value
                    val epoch = desktopThumbEpoch
                    val cached = desktopThumbCache[path]
                    val bmp by produceState(initialValue = cached, path, epoch) {
                        if (path.isBlank()) {
                            value = null
                            return@produceState
                        }
                        desktopThumbCache[path]?.let {
                            value = it
                            return@produceState
                        }
                        value = withContext(Dispatchers.IO) {
                            runCatching {
                                DesktopImageDecoder.decodeThumbnail(File(path), maxEdgePx = 96)
                            }.getOrNull()
                        }?.also { desktopThumbCache[path] = it }
                    }
                    val job = remember(
                        info.uri,
                        exportJobState.completedCount,
                        exportJobState.isSaving,
                        exportJobState.isFinished,
                    ) { info.jobState }
                    me.rosuh.easywatermark.ui.save.ExportProgressOverlay(
                        jobState = job,
                        modifier = thumbModifier,
                    ) {
                        if (bmp != null) {
                            Image(
                                bitmap = bmp!!,
                                contentDescription = null,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.fillMaxSize(),
                            )
                        } else {
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .background(MaterialTheme.colorScheme.surfaceVariant),
                            )
                        }
                    }
                }
            }
        }
    }
}
