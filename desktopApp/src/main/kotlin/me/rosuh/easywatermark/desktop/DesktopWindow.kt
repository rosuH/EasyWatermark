package me.rosuh.easywatermark.desktop

import androidx.compose.foundation.Image
import androidx.compose.foundation.draganddrop.dragAndDropTarget
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.runtime.rememberUpdatedState
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
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.IntSize
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
import me.rosuh.easywatermark.data.model.MediaRef
import me.rosuh.easywatermark.data.model.TextPaintStyle
import me.rosuh.easywatermark.data.model.TextTypeface
import me.rosuh.easywatermark.data.model.UserPreferences
import me.rosuh.easywatermark.data.model.WaterMark
import me.rosuh.easywatermark.data.model.WatermarkConfigChange
import me.rosuh.easywatermark.data.model.WatermarkTileMode
import me.rosuh.easywatermark.data.repo.DesktopIconPersistence
import me.rosuh.easywatermark.data.repo.TemplateRepository
import me.rosuh.easywatermark.domain.OutputPrefsEditor
import me.rosuh.easywatermark.domain.TemplateEditor
import me.rosuh.easywatermark.domain.WatermarkConfigEditor
import me.rosuh.easywatermark.render.DesktopImageDecoder
import me.rosuh.easywatermark.render.DesktopPreviewRaster
import me.rosuh.easywatermark.render.DesktopRenderRequest
import me.rosuh.easywatermark.render.DesktopSaveDecision
import me.rosuh.easywatermark.render.DesktopWatermarkComposer
import me.rosuh.easywatermark.session.AppIntent
import me.rosuh.easywatermark.session.DesktopExportPipelinePort
import me.rosuh.easywatermark.session.DesktopSaveAsDestination
import me.rosuh.easywatermark.session.DesktopSessionImport
import me.rosuh.easywatermark.session.WatermarkSessionViewModel
import me.rosuh.easywatermark.shared.generated.resources.Res
import me.rosuh.easywatermark.shared.generated.resources.desktop_drop_busy
import me.rosuh.easywatermark.shared.generated.resources.desktop_drop_unsupported
import me.rosuh.easywatermark.shared.generated.resources.desktop_import_failed
import me.rosuh.easywatermark.shared.generated.resources.desktop_imported
import me.rosuh.easywatermark.shared.generated.resources.desktop_importing
import me.rosuh.easywatermark.shared.generated.resources.desktop_ready_status
import me.rosuh.easywatermark.shared.generated.resources.desktop_save_as
import me.rosuh.easywatermark.shared.generated.resources.desktop_save_as_dialog_title
import me.rosuh.easywatermark.shared.generated.resources.desktop_save_as_failed
import me.rosuh.easywatermark.shared.generated.resources.desktop_saved_as
import me.rosuh.easywatermark.shared.generated.resources.dialog_export_to_gallery
import me.rosuh.easywatermark.shared.generated.resources.dialog_save_export_cd_done
import me.rosuh.easywatermark.shared.generated.resources.dialog_save_export_cd_progress
import me.rosuh.easywatermark.shared.generated.resources.dialog_save_export_done_failed
import me.rosuh.easywatermark.shared.generated.resources.dialog_save_export_done_partial
import me.rosuh.easywatermark.shared.generated.resources.dialog_save_export_done_success
import me.rosuh.easywatermark.shared.generated.resources.dialog_save_export_progress
import me.rosuh.easywatermark.shared.generated.resources.dialog_save_exporting
import me.rosuh.easywatermark.shared.generated.resources.dialog_save_destination_folder
import me.rosuh.easywatermark.shared.generated.resources.dialog_save_filename_policy_desktop
import me.rosuh.easywatermark.shared.generated.resources.dialog_save_export_counts
import me.rosuh.easywatermark.shared.generated.resources.dialog_save_success_where
import me.rosuh.easywatermark.shared.generated.resources.dialog_save_error_generic
import me.rosuh.easywatermark.shared.generated.resources.share
import me.rosuh.easywatermark.ui.sharedString
import me.rosuh.easywatermark.ui.EditorBottomControls
import me.rosuh.easywatermark.ui.EditorScreen
import me.rosuh.easywatermark.ui.editorLayoutClass
import me.rosuh.easywatermark.shared.generated.resources.dev_comment
import me.rosuh.easywatermark.ui.about.AboutDevCard
import me.rosuh.easywatermark.ui.about.AboutScreenIcons
import me.rosuh.easywatermark.ui.about.AboutScreen
import me.rosuh.easywatermark.ui.about.OpenSourceScreen
import me.rosuh.easywatermark.ui.EditorOptionItem
import me.rosuh.easywatermark.ui.EditorTemplateSheetHost
import me.rosuh.easywatermark.ui.label
import me.rosuh.easywatermark.ui.iconPainter
import me.rosuh.easywatermark.ui.ProductShellHost
import me.rosuh.easywatermark.ui.ProductShellNav
import me.rosuh.easywatermark.ui.SharedProductDrawables
import me.rosuh.easywatermark.ui.desktopClampPreviewOffsetDrag

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

import me.rosuh.easywatermark.platform.platformMotionPolicy
import me.rosuh.easywatermark.ui.theme.AppTheme
import me.rosuh.easywatermark.ui.theme.ProvideMotionPolicy
import org.jetbrains.compose.resources.stringResource
import java.awt.Desktop
import java.awt.FileDialog
import java.awt.datatransfer.DataFlavor
import java.io.File
import java.net.URI
import java.util.prefs.Preferences

/** Best-effort Open-dialog filename filter (honored on macOS; ignored on some platforms — harmless). */
// J3: capability-true extensions (WebP only if ImageIO can decode — stock JDK usually cannot).
private val IMAGE_EXTENSIONS: Set<String> =
    me.rosuh.easywatermark.render.DesktopImageFormats.chooserExtensions()

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

/**
 * Stable per-user app-data dir for the interactive Desktop window.
 * J3: [me.rosuh.easywatermark.platform.DesktopAppPaths.resolveAppDataDir] (OS-native + legacy
 * `~/.easywatermark` copy-forward). Headless/demo witnesses (`Main.kt`) stay under repo-local `build/`.
 */
private fun resolveDesktopAppDataDir(): File {
    val home = System.getProperty("user.home")?.takeIf { it.isNotBlank() }
    if (home == null) {
        System.err.println(
            "S4d-215/J3: user.home unavailable; persisting window state under repo-local build/desktop-app-data",
        )
    }
    return me.rosuh.easywatermark.platform.DesktopAppPaths.resolveAppDataDir(
        home = home,
        fallbackWhenNoHome = File("build/desktop-app-data"),
    )
}

/**
 * User-facing output dir for Save/Export batches — `~/Pictures` when it exists, else
 * `<app-data>/output`. Must not use repo-local `build/`. Headless defaults stay build-local.
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
 * Immutable same-item Preview/Save As input (C2 review-fix): one Session item owns path + offset.
 * File-level type so it is not a local class inside [launchDesktopWindow].
 */
private data class FrozenItemInput(
    val sourcePath: String?,
    val offsetX: Float,
    val offsetY: Float,
    val label: String,
)

/**
 * Compose Desktop product window.
 *
 * **Import-only:** Open / Add more / Drop enter or append Session selection — no output files, no export job.
 * **Write:** Save / Export (unique names under [resolveDesktopOutputDir]) and Save As (exact path).
 * **Preview:** app-private temp only; never becomes [lastSavedFile].
 */
fun launchDesktopWindow() = application {
    // Persist window state under OS-native app-data (J3 DesktopAppPaths; legacy ~/.easywatermark
    // copy-forward when empty). Headless/demo witnesses stay under build/ (Main.kt).
    val appDataDir = remember { resolveDesktopAppDataDir() }
    // Real Save/Export batch destination (unique names). Save As uses the user-chosen path exactly.
    val outputDir = remember { resolveDesktopOutputDir() }
    // ONE repository + editor for the window's lifetime (DataStore forbids a second active store per file).
    val repo = remember { DesktopWatermarkFlow.buildRepository(dir = appDataDir) }
    val editor = remember { WatermarkConfigEditor(repo) }
    // the output-prefs repo the save flow reads (empty store → the shared (JPEG, 80) default).
    val userConfigRepo = remember { DesktopWatermarkFlow.buildUserConfigRepository(dir = appDataDir) }
    // Shared session + Desktop export port (unique destination). Preview uses runSaveFlow temp;
    // Save As uses DesktopSaveAsDestination.renderAndSaveExact.
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
        mutableStateOf(sharedString(Res.string.desktop_ready_status))
    }
    // Surface shared export progress when an explicit Save/Export batch is running.
    LaunchedEffect(exportJobState.isSaving, exportJobState.completedCount, exportJobState.totalCount) {
        if (exportJobState.isSaving && exportJobState.totalCount > 0) {
            status = "Session export ${exportJobState.completedCount}/${exportJobState.totalCount}…"
        }
    }
    var busy by remember { mutableStateOf(false) }
    // Last REAL save only (explicit Export / Save As). Never Preview temp, never Open/Drop import.
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
    // Config / import preview refresh — debounced (slider ticks).
    var previewGeneration by remember { mutableStateOf(0) }
    // H0.1-fix: offset-only / draft preview gen — **no** 250ms debounce, light in-memory raster.
    var offsetPreviewGeneration by remember { mutableStateOf(0) }
    // Committed long-edge bucket from measured preview box (px). Draft paints stay at 720.
    var committedPreviewMaxEdgePx by remember {
        mutableStateOf(DesktopPreviewRaster.PREVIEW_MAX_EDGE_PX)
    }
    // UI-only CLAMP drag draft (never Session/export). selectionId + offsets.
    var clampDraft by remember { mutableStateOf<Triple<String, Float, Float>?>(null) }
    // E0: Session owns product route; FileDialog stays Desktop edge.
    val launchUi by session.launchScreenUiStateFlow.collectAsState()
    val productRoute = ProductShellNav.routeFromLaunchUi(launchUi.uiState)
    val sessionImages = launchUi.selectedImageList
    LaunchedEffect(Unit) {
        userConfigRepo.userPreferences.first().let {
            outputFormat = it.outputFormat
            outputQuality = it.compressLevel
        }
    }

    /**
     * Resolve one Session [ImageInfo] that owns **both** source path and offset.
     * E1: Session only — curImageInfo → first selected → fixture center (no host byte mirror).
     */
    fun freezeCurrentItemInput(): FrozenItemInput {
        val launch = session.launchScreenUiStateFlow.value
        val selected = launch.selectedImageList
        val item = launch.curImageInfo ?: selected.firstOrNull()
        if (item != null) {
            val path = item.uri.value
            return FrozenItemInput(
                sourcePath = path,
                offsetX = item.offsetX,
                offsetY = item.offsetY,
                label = path,
            )
        }
        return FrozenItemInput(
            sourcePath = null,
            offsetX = 0.5f,
            offsetY = 0.5f,
            label = "<generated 640x480 fixture>",
        )
    }

    /**
     * H0.1-fix: in-memory editor preview (decode+downscale+compose, **no** encode/temp).
     * Optional [overrideOffset] for UI draft or just-committed offset; default Session freeze.
     * [gen] drops stale async results (draft samples / rapid offset commits).
     */
    suspend fun refreshPreviewLight(
        gen: Int,
        overrideOffset: Pair<Float, Float>? = null,
        isDraft: Boolean = false,
    ): String {
        val bench = me.rosuh.easywatermark.ui.ClampDragBench.previewScope(
            if (isDraft) "desktop_draft_preview" else "desktop_offset_preview",
        )
        val frozen = freezeCurrentItemInput()
        val ox = overrideOffset?.first ?: frozen.offsetX
        val oy = overrideOffset?.second ?: frozen.offsetY
        val wm = repo.waterMark.first()
        val maxEdge = DesktopPreviewRaster.maxEdgeForPaint(
            isDraft = isDraft,
            committedBucketPx = committedPreviewMaxEdgePx,
        )
        val (img, msg) = withContext(Dispatchers.IO) {
            try {
                val imageBytes: ByteArray
                when {
                    frozen.sourcePath != null -> {
                        val file = File(frozen.sourcePath)
                        require(file.isFile) {
                            "Current Session image is missing or not a regular file: ${frozen.sourcePath}"
                        }
                        imageBytes = file.readBytes()
                    }
                    else -> {
                        imageBytes = DesktopWatermarkComposer.sampleBackgroundPng(width = 640, height = 480)
                    }
                }
                bench.mark("read")
                val iconBytes = if (wm.markMode == me.rosuh.easywatermark.data.model.WatermarkMode.Image &&
                    wm.iconUri.value.isNotBlank()
                ) {
                    val iconFile = File(wm.iconUri.value)
                    if (iconFile.isFile) iconFile.readBytes() else null
                } else {
                    null
                }
                val composed = DesktopPreviewRaster.renderWatermarked(
                    imageBytes = imageBytes,
                    waterMark = wm,
                    offsetX = ox,
                    offsetY = oy,
                    iconBytes = iconBytes,
                    maxEdgePx = maxEdge,
                )
                bench.mark("compose")
                composed to "Preview light ${composed.width}x${composed.height} maxEdge=$maxEdge"
            } catch (t: Throwable) {
                bench.mark("error")
                null to "Preview light failed: ${t.message}"
            }
        }
        // Drop stale generations (draft flurry or superseded offset commit).
        if (gen != offsetPreviewGeneration) {
            bench.finish(mapOf("staleGen" to true, "isDraft" to isDraft, "maxEdge" to maxEdge))
            return msg
        }
        img?.let { preview = it }
        bench.finish(
            mapOf(
                "offsetX" to ox,
                "offsetY" to oy,
                "hasPreview" to (img != null),
                "isDraft" to isDraft,
                "debounceMs" to 0,
                "saveFlow" to false,
                "maxEdge" to maxEdge,
            ),
        )
        return msg
    }

    /**
     * Measured preview-box size (px) → committed long-edge bucket.
     * Only acts when the bucket changes: one committed light-preview generation.
     * Same-bucket resize is a no-op.
     */
    fun onPreviewBoxSizeChanged(size: IntSize) {
        if (size.width <= 0 || size.height <= 0) return
        val next = DesktopPreviewRaster.committedMaxEdgePx(size.width, size.height)
        if (next == committedPreviewMaxEdgePx) return
        committedPreviewMaxEdgePx = next
        offsetPreviewGeneration += 1
    }

    // Config / import: keep 250ms debounce for high-frequency slider ticks, then kick light path.
    // Export / Save As still uses full DesktopRenderSaveSpine (committed Session offsets only).
    LaunchedEffect(previewGeneration) {
        if (previewGeneration == 0) return@LaunchedEffect
        val debounceBench = me.rosuh.easywatermark.ui.ClampDragBench.previewScope("desktop_config_preview_debounce")
        delay(250)
        debounceBench.mark("delay250")
        debounceBench.finish(mapOf("previewGeneration" to previewGeneration))
        // After debounce, request one light paint (no saveFlow).
        offsetPreviewGeneration += 1
    }

    // Single light-preview consumer: draft (override) or Session freeze. No debounce here.
    LaunchedEffect(offsetPreviewGeneration) {
        if (offsetPreviewGeneration == 0) return@LaunchedEffect
        val draft = clampDraft
        val override = draft?.let { it.second to it.third }
        status = refreshPreviewLight(
            gen = offsetPreviewGeneration,
            overrideOffset = override,
            isDraft = draft != null,
        )
    }

    /**
     * Import-only batch (Launch Open, editor Add more, Drop).
     * Updates Session selection and preview via [DesktopSessionImport.commitImport];
     * **never** writes output files or starts export.
     *
     * @param append false = replace selection (Launch Open); true = append unique paths (Add more / Drop).
     */
    fun openImageFilesBatch(files: List<File>, append: Boolean = false) {
        if (files.isEmpty()) return
        scope.launch {
            busy = true
            status = sharedString(Res.string.desktop_importing, files.size)
            try {
                val prior = session.launchScreenUiStateFlow.value.selectedImageList
                val (msg, ok) = withContext(Dispatchers.IO) {
                    try {
                        val selected = DesktopSessionImport.commitImport(
                            session = session,
                            files = files,
                            existingSelection = prior,
                            append = append,
                            waterMark = repo.waterMark.first(),
                        )
                        // Session paths own source identity — no host full-resolution byte mirror.
                        sharedString(Res.string.desktop_imported, selected.size) to true
                    } catch (t: Throwable) {
                        sharedString(Res.string.desktop_import_failed, t.message ?: "") to false
                    }
                }
                // openImageFilesBatch → Session EnterEditor (productRoute / selection from Session).
                // Preview only — never touch lastSavedFile (import is not an explicit save).
                // H0.1-fix: light in-memory preview (no saveFlow); clear any CLAMP draft.
                if (ok) {
                    clampDraft = null
                    offsetPreviewGeneration += 1
                    val gen = offsetPreviewGeneration
                    status = "$msg · ${refreshPreviewLight(gen = gen, isDraft = false)}"
                } else {
                    status = msg
                }
            } finally {
                busy = false
            }
        }
    }

    // Drop → same import-only batch as Open / Add more (append when editor already has images).
    val importBatchLatest = rememberUpdatedState(
        newValue = { files: List<File>, append: Boolean -> openImageFilesBatch(files, append) },
    )
    val busyLatest = rememberUpdatedState(busy)
    val dropTarget = remember {
        object : DragAndDropTarget {
            override fun onDrop(event: DragAndDropEvent): Boolean {
                if (busyLatest.value) {
                    status = sharedString(Res.string.desktop_drop_busy)
                    return false
                }
                val files = supportedImageFiles(event)
                if (files.isEmpty()) {
                    status = sharedString(
                        Res.string.desktop_drop_unsupported,
                        IMAGE_EXTENSIONS.joinToString(", "),
                    )
                    return false
                }
                // Drop is import-only: same openImageFilesBatch as Open / Add more.
                val append = session.launchScreenUiStateFlow.value.selectedImageList.isNotEmpty()
                importBatchLatest.value(files, append)
                return true
            }
        }
    }

    /**
     * Save As: production exact-write via [DesktopSaveAsDestination.renderAndSaveExact]
     * (not [DesktopSaveDecision.resolveUniqueOutputFile], not preview temp).
     */
    fun saveAsExactPath(window: java.awt.Frame, dialogTitle: String) {
        if (busy) return
        val dialog = FileDialog(window, dialogTitle, FileDialog.SAVE).apply {
            val fmt = outputFormat
            file = "watermarked.${fmt.fileExtension}"
            isVisible = true
        }
        val dir = dialog.directory ?: return
        val name = dialog.file ?: return
        val userChosen = File(dir, name)
        scope.launch {
            busy = true
            try {
                // Immutable job snapshot before filesystem IO (C2 attempt 2):
                // same-item path+offset + config + prefs → DesktopRenderRequest, then IO only reads
                // bytes / render / exact write with that frozen request.
                val frozen = freezeCurrentItemInput()
                val config = repo.waterMark.first()
                val prefs = userConfigRepo.userPreferences.first()
                val request = DesktopRenderRequest(
                    config = config,
                    prefs = prefs,
                    offsetX = frozen.offsetX,
                    offsetY = frozen.offsetY,
                )
                val out = withContext(Dispatchers.IO) {
                    val imageBytes: ByteArray = when {
                        frozen.sourcePath != null -> {
                            val file = File(frozen.sourcePath)
                            require(file.isFile) {
                                "Current Session image is missing or not a regular file: ${frozen.sourcePath}"
                            }
                            file.readBytes()
                        }
                        else -> DesktopWatermarkComposer.sampleBackgroundPng(width = 640, height = 480)
                    }
                    val saved = DesktopSaveAsDestination.renderAndSaveExact(
                        imageBytes = imageBytes,
                        request = request,
                        userChosen = userChosen,
                    )
                    File(saved.output.value)
                }
                // Explicit Save As branch — track as last real save for Reveal/Open folder.
                lastSavedFile = out
                status = sharedString(Res.string.desktop_saved_as, out.path)
            } catch (t: Throwable) {
                status = sharedString(Res.string.desktop_save_as_failed, t.message ?: "")
            } finally {
                busy = false
            }
        }
    }

    // E2 close policy: cancel in-flight export, then exit. Durable WaterMark DataStore is not wiped.
    // Presentation-only state (preview bitmap, sheets) dies with the process; Session product route
    // is not mirrored as a host owner. Editor back uses session.onBackPressed() → Launch + discard batch.
    fun closeDesktopWindow() {
        session.cancelExport()
        exitApplication()
    }

    Window(onCloseRequest = ::closeDesktopWindow, title = "EasyWatermark — Desktop") {
        AppTheme(darkTheme = true) {
            // I3: Desktop platformMotionPolicy is Full (no OS reduce-motion API).
            ProvideMotionPolicy(platformMotionPolicy()) {
            // E1: Session-only current for filmstrip (no host selectedSessionImage mirror).
            val selectedForStrip = launchUi.curImageInfo
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

            // Drop is import-only on Launch and Editor (never writes output).
            val shellModifier = Modifier.fillMaxSize()
                .dragAndDropTarget(shouldStartDragAndDrop = { hasFileList(it) }, target = dropTarget)
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
                                openImageFilesBatch(files)
                            }
                        },
                        onGoAbout = {
                            session.openAbout(me.rosuh.easywatermark.ui.LaunchScreenUiState.Launch)
                        },
                        logo = { modifier, shouldAnimate ->
                            me.rosuh.easywatermark.ui.BrandLogo(
                                modifier = modifier,
                                animate = shouldAnimate,
                            )
                        },
                        modifier = shellModifier,
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
                            session.onBackPressed()
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
                        modifier = shellModifier,
                    )
                }
                ProductShellNav.Route.Editor -> {
                    var colorDraft by remember(waterMark.textColor) {
                        mutableStateOf(formatArgbHexColor(waterMark.textColor))
                    }
                    // I1: window size in Dp → pure EditorLayoutClass (Expanded on typical Desktop).
                    BoxWithConstraints(modifier = shellModifier) {
                    val layoutClass = remember(maxWidth, maxHeight) {
                        editorLayoutClass(maxWidth.value, maxHeight.value)
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
                            // C4.4R.2 + H0.1-fix: CLAMP drag → UI draft paint + one applyOffset
                            // at end; offset preview uses light raster (no 250ms/saveFlow).
                            Box(
                                modifier = previewModifier
                                    .fillMaxSize()
                                    .onSizeChanged { size -> onPreviewBoxSizeChanged(size) },
                                contentAlignment = Alignment.Center,
                            ) {
                                val bmp = preview
                                if (bmp != null) {
                                    val dragItem = selectedForStrip
                                    Image(
                                        bitmap = bmp,
                                        contentDescription = "Watermark preview",
                                        contentScale = ContentScale.Fit,
                                        modifier = Modifier
                                            .fillMaxSize()
                                            .padding(12.dp)
                                            .desktopClampPreviewOffsetDrag(
                                                enabled = !busy && dragItem != null,
                                                selectionId = dragItem?.uri?.value.orEmpty(),
                                                isClamp = waterMark.tileMode == WatermarkTileMode.CLAMP,
                                                imageWidth = bmp.width.toFloat(),
                                                imageHeight = bmp.height.toFloat(),
                                                offsetX = dragItem?.offsetX ?: 0.5f,
                                                offsetY = dragItem?.offsetY ?: 0.5f,
                                                onOffsetDraft = { x, y ->
                                                    val id = dragItem?.uri?.value.orEmpty()
                                                    if (id.isEmpty()) return@desktopClampPreviewOffsetDrag
                                                    clampDraft = Triple(id, x, y)
                                                    offsetPreviewGeneration++
                                                },
                                                onOffsetDraftClear = {
                                                    clampDraft = null
                                                },
                                                onOffsetCommit = { x, y ->
                                                    // Fail-closed: live Session curImageInfo.uri must match drag.
                                                    val dragUri = dragItem?.uri
                                                        ?: return@desktopClampPreviewOffsetDrag
                                                    val item = session.launchScreenUiStateFlow.value
                                                        .curImageInfo
                                                        ?.takeIf { it.uri == dragUri }
                                                        ?: return@desktopClampPreviewOffsetDrag
                                                    // H0.1-fix: sync commit; immediate light preview.
                                                    val b = me.rosuh.easywatermark.ui.ClampDragBench
                                                        .previewScope("desktop_offset_commit")
                                                    session.applyOffset(
                                                        item.copy(offsetX = x, offsetY = y),
                                                    )
                                                    b.mark("applyOffset")
                                                    clampDraft = null
                                                    offsetPreviewGeneration++
                                                    b.mark("offsetPreviewGenerationBump")
                                                    b.finish(
                                                        mapOf(
                                                            "offsetX" to x,
                                                            "offsetY" to y,
                                                            "debounceMs" to 0,
                                                            "saveFlow" to false,
                                                        ),
                                                    )
                                                },
                                            ),
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
                        onBack = { session.onBackPressed() },
                        onAddMoreImages = {
                            val dialog = FileDialog(window, "Open image", FileDialog.LOAD).apply {
                                isMultipleMode = true
                                setFilenameFilter { _, fileName ->
                                    fileName.substringAfterLast('.', "").lowercase() in IMAGE_EXTENSIONS
                                }
                                isVisible = true
                            }
                            val files = DesktopSaveDecision.supportedImageFiles(dialog.files.toList(), IMAGE_EXTENSIONS)
                            if (files.isNotEmpty()) openImageFilesBatch(files, append = true)
                        },
                        onShowSaveDialog = {
                            // Open Android-parity export panel; do not write files until primary CTA.
                            session.resetJobStatus()
                            showSaveSheet = true
                        },
                        onGoAboutScreen = {
                            session.openAbout(me.rosuh.easywatermark.ui.LaunchScreenUiState.Editor)
                        },
                        onImageSelected = { info ->
                            // E1: await Session SelectCurrent before light-preview freeze so path+offset
                            // match the selected item (no host byte mirror; no race on previous focus).
                            scope.launch {
                                session.dispatchAndAwait(
                                    AppIntent.SelectCurrent(info.uri),
                                )
                                previewGeneration++
                            }
                        },
                        onConfigChange = { change ->
                            // F2: typed WatermarkConfigChange from shared controls (no from()).
                            if (!busy) {
                                scope.launch {
                                    busy = true
                                    val (msg, ok) = withContext(Dispatchers.IO) {
                                        try {
                                            session.dispatchAndAwait(
                                                AppIntent.ApplyConfig(change),
                                            )
                                            "Applied ${change::class.simpleName}" to true
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
                        modifier = Modifier.fillMaxSize(),
                        layoutClass = layoutClass,
                    )
                    } // BoxWithConstraints Editor
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
                val exportItems = sessionImages
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
                val exportTotalFixed = exportItems.size
                val recovery = me.rosuh.easywatermark.ui.save.ExportRecoveryUi.fromJob(
                    isSaving = exportJobState.isSaving,
                    isFinished = exportJobState.isFinished,
                    successCount = exportJobState.successCount.coerceAtLeast(exportJobState.completedCount),
                    failureCount = exportJobState.failureCount,
                    processedCount = exportJobState.processedCount
                        .coerceAtLeast(exportJobState.successCount + exportJobState.failureCount),
                    totalCount = exportJobState.totalCount.takeIf { it > 0 } ?: exportTotalFixed,
                )
                val primaryLabel = when {
                    recovery.isExporting -> stringResource(Res.string.dialog_save_exporting)
                    recovery.isFinished -> stringResource(Res.string.share)
                    else -> stringResource(Res.string.dialog_export_to_gallery)
                }
                val resultSummaryText = when {
                    recovery.isExporting -> stringResource(
                        Res.string.dialog_save_export_progress,
                        recovery.processedCount,
                        recovery.totalCount.coerceAtLeast(1),
                    )
                    recovery.isFinished && recovery.failureCount == 0 && recovery.successCount > 0 ->
                        stringResource(
                            Res.string.dialog_save_export_done_success,
                            recovery.successCount,
                            recovery.totalCount.coerceAtLeast(1),
                        )
                    recovery.isFinished && recovery.successCount > 0 && recovery.failureCount > 0 ->
                        stringResource(
                            Res.string.dialog_save_export_done_partial,
                            recovery.successCount,
                            recovery.totalCount.coerceAtLeast(1),
                            recovery.failureCount,
                        )
                    recovery.isFinished && recovery.successCount == 0 ->
                        stringResource(
                            Res.string.dialog_save_export_done_failed,
                            recovery.totalCount.coerceAtLeast(1),
                        )
                    else -> "${recovery.successCount}/${recovery.totalCount.coerceAtLeast(1)}"
                }
                val statusCd = if (recovery.isExporting) {
                    stringResource(
                        Res.string.dialog_save_export_cd_progress,
                        recovery.processedCount,
                        recovery.totalCount.coerceAtLeast(1),
                        recovery.successCount,
                        recovery.failureCount,
                    )
                } else {
                    stringResource(
                        Res.string.dialog_save_export_cd_done,
                        recovery.processedCount
                            .coerceAtLeast(recovery.successCount + recovery.failureCount),
                        recovery.successCount,
                        recovery.failureCount,
                        recovery.totalCount.coerceAtLeast(1),
                    )
                }
                val destinationLine = stringResource(
                    Res.string.dialog_save_destination_folder,
                    outputDir.path,
                )
                val filenamePolicyLine = stringResource(Res.string.dialog_save_filename_policy_desktop)
                val countsLine = if (recovery.isExporting || recovery.isFinished) {
                    stringResource(
                        Res.string.dialog_save_export_counts,
                        recovery.processedCount
                            .coerceAtLeast(recovery.successCount + recovery.failureCount),
                        recovery.successCount,
                        recovery.failureCount,
                    )
                } else {
                    ""
                }
                val outcomeDetailLine = when {
                    recovery.isAllSuccess || recovery.isPartial ->
                        stringResource(
                            Res.string.dialog_save_success_where,
                            recovery.successCount,
                            outputDir.path,
                        )
                    recovery.isAllFailed ->
                        stringResource(Res.string.dialog_save_error_generic)
                    else -> ""
                }
                // Desktop-only Save As (exact path) — not unique batch export naming.
                val saveAsLabel = stringResource(Res.string.desktop_save_as)
                val saveAsDialogTitle = stringResource(Res.string.desktop_save_as_dialog_title)
                val exportErrorGeneric = stringResource(Res.string.dialog_save_error_generic)
                val runBatchExport: () -> Unit = {
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
                                // Explicit batch Export branch — track last real save for Reveal.
                                last?.let { lastSavedFile = it }
                                val exp = session.exportJobState.value
                                // I0: counts + destination path; no raw exception text.
                                status = "Exported ${exp.successCount}/${exp.totalCount} " +
                                    "(${exp.failureCount} failed) → ${outputDir.path}"
                            } else {
                                // Fixture-only path (no session selection). Not a Session batch —
                                // markExportFinished is allowed here only (D5 U4).
                                val out = withContext(Dispatchers.IO) {
                                    val fmt = userConfigRepo.userPreferences.first().outputFormat
                                    val target = DesktopSaveDecision.resolveUniqueOutputFile(outputDir, fmt)
                                    val o = DesktopWatermarkFlow.runSaveFlow(
                                        repo, userConfigRepo, outputFile = target,
                                        offsetX = 0.5f, offsetY = 0.5f,
                                    )
                                    File(o.outputPath)
                                }
                                lastSavedFile = out
                                session.markExportFinished(completedCount = 1, totalCount = 1)
                                status = "Saved: ${out.path}"
                            }
                        } catch (_: Throwable) {
                            // I0: never surface raw Throwable.message in product chrome.
                            status = exportErrorGeneric
                        } finally {
                            busy = false
                        }
                    }
                }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.End,
                ) {
                    Button(
                        onClick = { saveAsExactPath(window, dialogTitle = saveAsDialogTitle) },
                        enabled = !exportJobState.isSaving && !busy,
                    ) {
                        Text(saveAsLabel)
                    }
                }
                SaveExportSheetShell(
                    items = exportItems,
                    selectedFormat = outputFormat,
                    quality = outputQuality,
                    primaryActionLabel = primaryLabel,
                    primaryActionEnabled = !exportJobState.isSaving && !busy,
                    // Open folder depends on last explicit save (Export or Save As), not batch finished.
                    showOpenGallery = lastSavedFile != null && !exportJobState.isSaving,
                    exportListSubtitle = resultSummaryText,
                    imageCount = exportTotalFixed,
                    isExporting = recovery.isExporting,
                    showCancelButton = recovery.showCancel,
                    onCancelClick = { session.cancelExport() },
                    showRetryFailedButton = recovery.showRetryFailed,
                    onRetryFailedClick = { runBatchExport() },
                    statusContentDescription = statusCd,
                    destinationLine = destinationLine,
                    filenamePolicyLine = filenamePolicyLine,
                    countsLine = countsLine,
                    outcomeDetailLine = outcomeDetailLine,
                    itemKey = { it.uri.value },
                    // Prefer already-decoded filmstrip/export thumbs; ImageInfo 1×1 is unknown.
                    itemAspectRatio = { info ->
                        val thumb = desktopThumbCache[info.uri.value]
                        when {
                            thumb != null && thumb.width > 1 && thumb.height > 1 ->
                                thumb.width.toFloat() / thumb.height.toFloat()
                            info.width > 1 && info.height > 1 ->
                                info.width.toFloat() / info.height.toFloat()
                            else -> null
                        }
                    },
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
                            // E09 share substitute: reveal folder of last real save (never preview).
                            val file = lastSavedFile
                            if (file != null && Desktop.isDesktopSupported()) {
                                try {
                                    Desktop.getDesktop().open(file.parentFile ?: file)
                                } catch (_: Throwable) {
                                    status = exportErrorGeneric
                                }
                            }
                        } else {
                            runBatchExport()
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
                        exportJobState.processedCount,
                        exportJobState.successCount,
                        exportJobState.failureCount,
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
            } // ProvideMotionPolicy
        } // AppTheme
    } // Window
}
