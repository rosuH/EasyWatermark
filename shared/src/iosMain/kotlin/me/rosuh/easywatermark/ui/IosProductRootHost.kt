package me.rosuh.easywatermark.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.ComposeUIViewController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import me.rosuh.easywatermark.session.IosSourceStager
import me.rosuh.easywatermark.session.IOS_STAGING_MAX_CONCURRENCY
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import me.rosuh.easywatermark.ProductVersion
import me.rosuh.easywatermark.data.db.buildTemplateDatabase
import me.rosuh.easywatermark.data.model.ImageFormat
import me.rosuh.easywatermark.data.model.ImageInfo
import me.rosuh.easywatermark.data.model.JobState
import me.rosuh.easywatermark.data.model.MediaRef
import me.rosuh.easywatermark.data.model.WaterMark
import me.rosuh.easywatermark.data.model.WatermarkConfigChange
import me.rosuh.easywatermark.data.model.WatermarkTileMode
import me.rosuh.easywatermark.data.model.entity.Template
import me.rosuh.easywatermark.data.repo.IosIconPersistence
import me.rosuh.easywatermark.data.repo.TemplateRepository
import me.rosuh.easywatermark.domain.OutputPrefsEditor
import me.rosuh.easywatermark.domain.TemplateEditor
import androidx.compose.ui.graphics.ImageBitmap
import me.rosuh.easywatermark.render.IosByteArrayInterop
import me.rosuh.easywatermark.render.IosImageDecoder
import me.rosuh.easywatermark.render.IosPreviewBench
import me.rosuh.easywatermark.render.IosPreviewRaster
import me.rosuh.easywatermark.session.AppIntent
import me.rosuh.easywatermark.session.IosAppServices
import me.rosuh.easywatermark.session.defaultIosAppServices
import me.rosuh.easywatermark.shared.generated.resources.Res
import me.rosuh.easywatermark.shared.generated.resources.action_pick
import me.rosuh.easywatermark.shared.generated.resources.dev_comment
import me.rosuh.easywatermark.shared.generated.resources.dialog_export_to_gallery
import me.rosuh.easywatermark.shared.generated.resources.dialog_save_export_cd_done
import me.rosuh.easywatermark.shared.generated.resources.dialog_save_export_cd_progress
import me.rosuh.easywatermark.shared.generated.resources.dialog_save_export_done_failed
import me.rosuh.easywatermark.shared.generated.resources.dialog_save_export_done_partial
import me.rosuh.easywatermark.shared.generated.resources.dialog_save_export_done_success
import me.rosuh.easywatermark.shared.generated.resources.dialog_save_export_progress
import me.rosuh.easywatermark.shared.generated.resources.dialog_save_exporting
import me.rosuh.easywatermark.shared.generated.resources.share
import me.rosuh.easywatermark.ui.about.AboutDevCard
import me.rosuh.easywatermark.ui.about.AboutScreen
import me.rosuh.easywatermark.ui.about.AboutScreenIcons
import me.rosuh.easywatermark.ui.about.OpenSourceScreen
import me.rosuh.easywatermark.ui.compose.IconWatermarkOption
import me.rosuh.easywatermark.ui.compose.TextColorOption
import me.rosuh.easywatermark.ui.compose.formatArgbHexColor
import me.rosuh.easywatermark.ui.save.SaveExportSheetShell
import me.rosuh.easywatermark.ui.theme.AppTheme
import org.jetbrains.compose.resources.stringResource
import platform.Foundation.NSData
import platform.Foundation.NSUserDefaults
import platform.Foundation.dataWithContentsOfFile
import platform.UIKit.UIViewController
import kotlin.experimental.ExperimentalObjCName
import kotlin.native.ObjCName

/**
 * iOS product root — production UI is shared [LaunchScreen] / [EditorScreen] / [AboutScreen]
 * plus shared [SaveExportSheetShell] for export panel interactions (C2 / Android Compose parity).
 * Swift only: PHPicker / Share / Save-to-Photos / open URL system edges.
 */
@OptIn(ExperimentalObjCName::class)
@ObjCName(name = "IosProductRootHost", exact = true)
class IosProductRootHost(
    private val onPickPhoto: () -> Unit,
    private val onPickIcon: () -> Unit,
    private val onShare: (filePath: String) -> Unit,
    /**
     * D4: Photos persistence edge for Swift.
     * Signature: `(bytes) { success, message -> … }` — must call the completion after
     * `PHPhotoLibrary.performChanges` finishes (not fire-and-forget).
     * Kotlin awaits each item before counting a persisted success.
     */
    private val onSaveToPhotos: (encodedBytes: ByteArray, onComplete: (Boolean, String?) -> Unit) -> Unit,
    private val onOpenUrl: (url: String) -> Unit = {},
    private val services: IosAppServices = defaultIosAppServices(),
) {
    private val photosSaveEdge: IosPhotosSaveEdge =
        IosPhotosSaveEdge { bytes, onComplete -> onSaveToPhotos(bytes, onComplete) }
    private val templateRepo by lazy {
        TemplateRepository(
            buildTemplateDatabase().templateDao(),
            Dispatchers.Default,
        )
    }
    private val templateEditor by lazy { TemplateEditor(templateRepo) }
    private val outputEditor by lazy { OutputPrefsEditor(services.userConfigRepo) }
    /** Host-owned scope for background stage/preview work (not GlobalScope). */
    private val hostScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    /**
     * App-owned staged source paths (`ewm_src_*`) for this host generation (E2 dispose).
     * Mutated on Main / host-scoped jobs only — no concurrent writer races with dispose.
     */
    private val ownedStagedPaths = linkedSetOf<String>()
    private var disposed = false

    /**
     * G4 file-first: host no longer permanently owns multi-item full-res source bytes.
     * Preview / export re-read staged `ewm_src_*` paths. Field retained only for dispose clear.
     */
    private var sourceBytes by mutableStateOf<ByteArray?>(null)
    private var iconBytes by mutableStateOf<ByteArray?>(null)
    /** In-memory watermarked preview (no PNG round-trip). */
    private var previewBitmap by mutableStateOf<ImageBitmap?>(null)
    /** Source path that [previewBitmap] was rendered for. */
    private var previewSourcePath by mutableStateOf<String?>(null)
    private var outputPath by mutableStateOf<String?>(null)
    private var statusLine by mutableStateOf("")
    private var isBusy by mutableStateOf(false)
    /**
     * Android BitmapCache analogue: watermarked [ImageBitmap] by source path.
     * Cleared on config change / memory pressure. Budget: [WM_PREVIEW_CACHE_MAX].
     */
    private val wmPreviewCache = mutableMapOf<String, ImageBitmap>()
    /** Source-only placeholders (no watermark). Budget: [PLACEHOLDER_CACHE_MAX]. */
    private val sourcePlaceholderCache = mutableMapOf<String, ImageBitmap>()
    /**
     * Filmstrip cell cache (path → small bitmap). Prefetched when a batch is staged so
     * fling does not cold-decode / flash empty cells. Budget: [FILMSTRIP_THUMB_CACHE_MAX].
     */
    private val filmstripThumbCache = mutableMapOf<String, ImageBitmap>()
    /** Bumped after prefetch so produceState re-reads the map. */
    private var filmstripThumbEpoch by mutableStateOf(0)
    /** Export-sheet thumbs. Budget: [EXPORT_THUMB_CACHE_MAX]. */
    private val exportThumbCache = mutableMapOf<String, ImageBitmap>()
    private var previewGen: Int = 0
    /**
     * H0.1-fix: UI-only CLAMP draft offset for live preview paint.
     * Never written to Session / export / DataStore. Cleared on gesture end/cancel.
     */
    private var clampDraftOffset by mutableStateOf<Pair<Float, Float>?>(null)
    private var clampDraftSelectionId by mutableStateOf<String?>(null)
    private var isSaving by mutableStateOf(false)
    /**
     * Presentation-only optimistic editor shell while picker IO runs (Session still Launch).
     * E0: Session is product-route owner; this flag only bridges shell until EnterEditor lands.
     */
    private var showEditor by mutableStateOf(false)
    /** C2: shared Android Compose export panel; Photos/Share stay Swift callbacks. */
    private var showSaveSheet by mutableStateOf(false)
    private var outputFormat by mutableStateOf(ImageFormat.JPEG)
    private var outputQuality by mutableStateOf(80)
    /** After a successful sheet export, primary CTA flips to Share (Android parity). */
    private var sheetExportFinished by mutableStateOf(false)
    /** Open-source licenses overlay (Android showOpenSource parity). */
    private var showOpenSource by mutableStateOf(false)
    /**
 * iOS has no Material You; still persist a force flag (Android CMonet parity) so the About
 * switch is interactive and sticky across launches.
     */
    private var dynamicColorForced by mutableStateOf(IosDynamicColorPrefs.isForced())

    private fun openAboutFromLaunch() {
        services.session.openAbout(LaunchScreenUiState.Launch)
    }

    private fun openAboutFromEditor() {
        services.session.openAbout(LaunchScreenUiState.Editor)
    }

    private fun closeAbout() {
        showOpenSource = false
        services.session.onBackPressed()
    }

    /** Swift edge: whether the product root is currently showing the editor. */
    fun isInEditor(): Boolean {
        val ui = services.session.launchScreenUiStateFlow.value.uiState
        return ui == LaunchScreenUiState.Editor || showEditor
    }

    /**
     * Issue 26 / C4.4R.S1 **test seam only** — observe preview-path identity after
     * [deliverPickedPhotosBatch]. Not a second product source of truth.
     */
    internal data class PreviewIdentitySnapshot(
        val previewSourcePath: String?,
        val wmCachePaths: Set<String>,
        val placeholderCachePaths: Set<String>,
    )

    /** Test-only read of host preview identity (wm/placeholder path caches). */
    internal fun previewIdentityForTests(): PreviewIdentitySnapshot =
        PreviewIdentitySnapshot(
            previewSourcePath = previewSourcePath,
            wmCachePaths = wmPreviewCache.keys.toSet(),
            placeholderCachePaths = sourcePlaceholderCache.keys.toSet(),
        )

    /** Test-only: whether [dispose] has completed at least once. */
    internal fun isDisposedForTests(): Boolean = disposed

    /** Test-only: paths still tracked as host-owned staged sources. */
    internal fun ownedStagedPathsForTests(): Set<String> = ownedStagedPaths.toSet()

    /** Test-only: register an app-owned staged path without a full picker deliver. */
    internal fun trackOwnedStagedPathForTests(path: String) {
        if (path.isNotBlank()) ownedStagedPaths.add(path)
    }

    /** Test-only: current entry counts for budgeted host image caches. */
    internal data class CacheBudgetSnapshot(
        val wmPreview: Int,
        val placeholder: Int,
        val filmstrip: Int,
        val exportThumb: Int,
        val holdsSourceBytes: Boolean,
    )

    internal fun cacheBudgetForTests(): CacheBudgetSnapshot =
        CacheBudgetSnapshot(
            wmPreview = wmPreviewCache.size,
            placeholder = sourcePlaceholderCache.size,
            filmstrip = filmstripThumbCache.size,
            exportThumb = exportThumbCache.size,
            holdsSourceBytes = sourceBytes != null,
        )

    /** Test-only: insert a placeholder cache entry and enforce budgets (no Session change). */
    internal fun putPlaceholderForTests(path: String, bitmap: ImageBitmap) {
        sourcePlaceholderCache[path] = bitmap
        enforceCacheBudgets()
    }

    /** Test-only: insert a wm preview cache entry and enforce budgets. */
    internal fun putWmPreviewForTests(path: String, bitmap: ImageBitmap) {
        wmPreviewCache[path] = bitmap
        enforceCacheBudgets()
    }

    /** Test-only: insert a filmstrip thumb and enforce budgets. */
    internal fun putFilmstripThumbForTests(path: String, bitmap: ImageBitmap) {
        filmstripThumbCache[path] = bitmap
        enforceCacheBudgets()
    }

    /**
     * G4 memory-pressure seam: clear host image caches and presentation bitmaps without
     * wiping Session product selection / route / owned staged path tracking.
     * Swift should call from `UIApplication.didReceiveMemoryWarningNotification`.
     *
     * Distinct from [dispose] (full teardown + temp delete + export cancel).
     */
    fun trimCaches() {
        if (disposed) return
        sourceBytes = null
        // Keep iconBytes: single small buffer needed for Image-mode editor chrome; Session still owns icon path.
        previewBitmap = null
        previewSourcePath = null
        previewGen += 1
        filmstripThumbEpoch += 1
        wmPreviewCache.clear()
        sourcePlaceholderCache.clear()
        filmstripThumbCache.clear()
        exportThumbCache.clear()
    }

    /** Alias for Swift / ObjC memory-warning bridge. */
    fun onMemoryWarning() = trimCaches()

    /**
     * FIFO eviction by insertion order when entry counts exceed G4 budgets.
     * Called after every cache put path (preview render, filmstrip prefetch, export thumb, tests).
     */
    private fun enforceCacheBudgets() {
        while (wmPreviewCache.size > WM_PREVIEW_CACHE_MAX) {
            val oldest = wmPreviewCache.keys.firstOrNull() ?: break
            wmPreviewCache.remove(oldest)
        }
        while (sourcePlaceholderCache.size > PLACEHOLDER_CACHE_MAX) {
            val oldest = sourcePlaceholderCache.keys.firstOrNull() ?: break
            sourcePlaceholderCache.remove(oldest)
        }
        while (filmstripThumbCache.size > FILMSTRIP_THUMB_CACHE_MAX) {
            val oldest = filmstripThumbCache.keys.firstOrNull() ?: break
            filmstripThumbCache.remove(oldest)
        }
        while (exportThumbCache.size > EXPORT_THUMB_CACHE_MAX) {
            val oldest = exportThumbCache.keys.firstOrNull() ?: break
            exportThumbCache.remove(oldest)
        }
    }

    /**
     * E2 host close/dispose (single-scene B1):
     * - cancel Session export
     * - invalidate preview generation
     * - clear bounded wm/placeholder/filmstrip/export caches
     * - remove app-owned staged temp paths from this host generation
     * - cancel host-scoped background work
     *
     * Idempotent: a second call is a no-op after the first full teardown.
     */
    fun dispose() {
        if (disposed) return
        disposed = true
        services.session.cancelExport()
        previewGen += 1
        hostScope.coroutineContext.cancelChildren()
        // Clear presentation + caches (Main-thread host; mutex for concurrent background writers).
        sourceBytes = null
        iconBytes = null
        previewBitmap = null
        previewSourcePath = null
        outputPath = null
        statusLine = ""
        isBusy = false
        isSaving = false
        showEditor = false
        showSaveSheet = false
        sheetExportFinished = false
        showOpenSource = false
        clampDraftOffset = null
        clampDraftSelectionId = null
        filmstripThumbEpoch += 1
        wmPreviewCache.clear()
        sourcePlaceholderCache.clear()
        filmstripThumbCache.clear()
        exportThumbCache.clear()
        val toDelete = ownedStagedPaths.toList()
        ownedStagedPaths.clear()
        toDelete.forEach { path ->
            if (path.contains("ewm_src_")) {
                IosSourceStager.deleteQuietly(path)
            }
        }
    }

    companion object {
        /** G4: watermarked preview cache entry cap. */
        const val WM_PREVIEW_CACHE_MAX: Int = 8
        /** G4: source-only placeholder cache entry cap. */
        const val PLACEHOLDER_CACHE_MAX: Int = 12
        /** G4: filmstrip thumb cache entry cap. */
        const val FILMSTRIP_THUMB_CACHE_MAX: Int = 48
        /** G4: export-sheet thumb cache entry cap. */
        const val EXPORT_THUMB_CACHE_MAX: Int = 48
    }

    fun viewController(): UIViewController = ComposeUIViewController {
        AppTheme(darkTheme = true) {
            val waterMark by services.waterMarkRepo.waterMark.collectAsState(WaterMark.default)
            val launchUi by services.session.launchScreenUiStateFlow.collectAsState()
            val exportJob by services.session.exportJobState.collectAsState()
            val sessionImages = launchUi.selectedImageList
            // E0: Session owns route; optimistic showEditor only while Session not yet Editor.
            val productRoute = when {
                launchUi.uiState == LaunchScreenUiState.About -> ProductShellNav.Route.About
                launchUi.uiState == LaunchScreenUiState.Editor || showEditor ->
                    ProductShellNav.Route.Editor
                else -> ProductShellNav.Route.Launch
            }
            val scope = rememberCoroutineScope()

            var templates by remember { mutableStateOf(emptyList<Template>()) }
            LaunchedEffect(productRoute) {
                if (productRoute == ProductShellNav.Route.Editor) {
                    templateRepo.getAllTemplate().collect { templates = it }
                } else {
                    templates = emptyList()
                }
            }
            LaunchedEffect(Unit) {
                services.userConfigRepo.userPreferences.first().let {
                    outputFormat = it.outputFormat
                    outputQuality = it.compressLevel
                }
            }

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
            val templateListPainter = SharedProductDrawables.templateListPainter()
            val avatarDevPainter = SharedProductDrawables.avatarDevPainter()
            val avatarToviPainter = SharedProductDrawables.avatarToviPainter()
            val devComment = stringResource(Res.string.dev_comment)

            // Shared product-shell transitions (Launch ↔ Editor ↔ About).
            ProductShellHost(
                route = productRoute,
                modifier = Modifier.fillMaxSize(),
            ) { route ->
                when (route) {
                ProductShellNav.Route.Launch -> {
                    LaunchScreen(
                        aboutIcon = aboutPainter,
                        onPickImage = onPickPhoto,
                        onGoAbout = { openAboutFromLaunch() },
                        logo = { modifier, animate ->
                            BrandLogo(modifier = modifier, animate = animate)
                        },
                    )
                }

                ProductShellNav.Route.About -> {
                    // Live watermark config (enableBounds for Show Bounds switch).
                    val aboutShowBounds = waterMark.enableBounds
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
                        // Match Android AboutScreenAndroid edge copy.
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
                        onBack = { closeAbout() },
                        onVersion = { onOpenUrl(ABOUT_URL_RELEASES) },
                        // App Store / market scheme is Android; use HTTPS product page on iOS.
                        onRate = { onOpenUrl(ABOUT_URL_RATE_IOS) },
                        onFeedback = { onOpenUrl(ABOUT_URL_ISSUES) },
                        onUpdateLog = { onOpenUrl(ABOUT_URL_RELEASES) },
                        onOpenSource = { showOpenSource = true },
                        onPrivacyZh = { onOpenUrl(ABOUT_URL_PRIVACY_ZH) },
                        onPrivacyEn = { onOpenUrl(ABOUT_URL_PRIVACY_EN) },
                        onDeveloper = { onOpenUrl(ABOUT_URL_DEV) },
                        onDesigner = { onOpenUrl(ABOUT_URL_DESIGNER) },
                        onToggleBounds = { enabled ->
                            scope.launch {
                                services.waterMarkRepo.toggleBounds(enabled)
                                // Bounds flag is persisted; clear WM preview cache so editor
                                // re-rasters on return (Android draws debug rects from config).
                                wmPreviewCache.clear()
                            }
                        },
                        onToggleDynamicColor = { enabled ->
                            IosDynamicColorPrefs.setForced(enabled)
                            dynamicColorForced = enabled
                            // Production toast: "Reboot and you'll get what you want."
                            // iOS has no Material You — flag is sticky for parity only.
                            statusLine = if (enabled) {
                                "Dynamic color flag on (Android Material You)"
                            } else {
                                "Dynamic color flag off"
                            }
                        },
                        // Production: large hero About logo + gradient animation.
                        logo = { logoModifier ->
                            me.rosuh.easywatermark.ui.AboutPageLogo(
                                modifier = logoModifier,
                                animate = true,
                            )
                        },
                    )
                }

                ProductShellNav.Route.Editor -> {
                    val displayPreview = previewBitmap
                    val iconBitmap = iconBytes?.let { bytes ->
                        remember(bytes) { IosImageDecoder.decode(bytes) }
                    }
                    var colorDraft by remember(waterMark.textColor) {
                        mutableStateOf(formatArgbHexColor(waterMark.textColor))
                    }

                    EditorScreen(
                        imageList = sessionImages,
                        waterMark = waterMark,
                        selectedImage = launchUi.curImageInfo ?: sessionImages.firstOrNull(),
                        templates = templates,
                        icons = EditorUiIcons(
                            // Production navigate-up is a back chevron (ic_back), not the brand logo.
                            back = backPainter,
                            addMoreImages = addPainter,
                            save = savePainter,
                            about = aboutPainter,
                            templateList = templateListPainter,
                        ),
                        preview = { previewModifier ->
                            // C4.4R.3: CLAMP drag on the Fit preview Image → session.applyOffset,
                            // selected-path cache eviction, one previewGen bump, existing rerender.
                            // Enable only when (1) path identity matches and (2) the displayed bitmap
                            // is the watermarked cache entry for that path — not a source placeholder
                            // (previewSourcePath is also set for unwatermarked placeholders).
                            val dragItem = launchUi.curImageInfo ?: sessionImages.firstOrNull()
                            val dragPath = dragItem?.uri?.value.orEmpty()
                            val draftActiveForSelection =
                                clampDraftSelectionId == dragPath && clampDraftOffset != null
                            val watermarkedDisplayMatchesSelection =
                                dragPath.isNotEmpty() &&
                                    previewSourcePath == dragPath &&
                                    displayPreview != null &&
                                    (
                                        wmPreviewCache[dragPath] === displayPreview ||
                                            draftActiveForSelection
                                        )
                            Box(
                                modifier = previewModifier
                                    .fillMaxSize()
                                    .testTag("sharedComposeWatermarkPreview"),
                                contentAlignment = Alignment.Center,
                            ) {
                                if (displayPreview != null) {
                                    Image(
                                        bitmap = displayPreview,
                                        contentDescription = "Watermarked preview",
                                        contentScale = ContentScale.Fit,
                                        modifier = Modifier
                                            .fillMaxSize()
                                            .clampPreviewOffsetDrag(
                                                enabled = !isBusy &&
                                                    dragItem != null &&
                                                    watermarkedDisplayMatchesSelection,
                                                selectionId = dragPath,
                                                isClamp = waterMark.tileMode ==
                                                    WatermarkTileMode.CLAMP,
                                                imageWidth = displayPreview.width.toFloat(),
                                                imageHeight = displayPreview.height.toFloat(),
                                                offsetX = dragItem?.offsetX ?: 0.5f,
                                                offsetY = dragItem?.offsetY ?: 0.5f,
                                                onOffsetDraft = { x, y ->
                                                    if (dragPath.isEmpty()) {
                                                        return@clampPreviewOffsetDrag
                                                    }
                                                    clampDraftOffset = x to y
                                                    clampDraftSelectionId = dragPath
                                                    previewGen += 1
                                                    val gen = previewGen
                                                    hostScope.launch {
                                                        try {
                                                            renderPreviewForCurrentSelection(
                                                                gen = gen,
                                                                draftOffset = x to y,
                                                            )
                                                        } catch (_: Throwable) {
                                                        }
                                                    }
                                                },
                                                onOffsetDraftClear = {
                                                    clampDraftOffset = null
                                                    clampDraftSelectionId = null
                                                },
                                                onOffsetCommit = { x, y ->
                                                    if (dragPath.isEmpty()) {
                                                        return@clampPreviewOffsetDrag
                                                    }
                                                    // Triple identity: frozen drag path, displayed
                                                    // preview path, and live Session selection.
                                                    if (previewSourcePath != dragPath) {
                                                        return@clampPreviewOffsetDrag
                                                    }
                                                    if (
                                                        wmPreviewCache[dragPath] !== displayPreview &&
                                                        !draftActiveForSelection
                                                    ) {
                                                        return@clampPreviewOffsetDrag
                                                    }
                                                    val live = services.session
                                                        .launchScreenUiStateFlow
                                                        .value
                                                        .curImageInfo
                                                        ?.takeIf { it.uri.value == dragPath }
                                                        ?: return@clampPreviewOffsetDrag
                                                    // H0.1-fix: sync Session commit; draft cleared
                                                    // by adapter after this callback.
                                                    val commitBench = ClampDragBench
                                                        .previewScope("ios_offset_commit")
                                                    services.session.applyOffset(
                                                        live.copy(offsetX = x, offsetY = y),
                                                    )
                                                    commitBench.mark("applyOffset")
                                                    clampDraftOffset = null
                                                    clampDraftSelectionId = null
                                                    wmPreviewCache.remove(dragPath)
                                                    commitBench.mark("cacheEvict")
                                                    previewGen++
                                                    val gen = previewGen
                                                    commitBench.mark("previewGenBump")
                                                    commitBench.finish(
                                                        mapOf(
                                                            "offsetX" to x,
                                                            "offsetY" to y,
                                                            "path" to dragPath.substringAfterLast('/'),
                                                        ),
                                                    )
                                                    hostScope.launch {
                                                        try {
                                                            renderPreviewForCurrentSelection(
                                                                gen = gen,
                                                            )
                                                        } catch (t: Throwable) {
                                                            statusLine =
                                                                "Preview failed: ${t.message}"
                                                        }
                                                    }
                                                },
                                            ),
                                    )
                                }
                                // Silent empty — never show "Loading…" in the top-left.
                                // Failures stay out of the chrome (statusLine is diagnostic only).
                            }
                        },
                        thumbnail = { imageInfo, contentDescription, thumbModifier ->
                            // Prefer prefetched host cache; produceState only on cold miss.
                            val path = imageInfo.uri.value
                            val epoch = filmstripThumbEpoch
                            val cached = filmstripThumbCache[path]
                            val thumbBitmap by produceState(initialValue = cached, path, epoch) {
                                if (path.isBlank() || path == "preview") {
                                    value = null
                                    return@produceState
                                }
                                filmstripThumbCache[path]?.let {
                                    value = it
                                    return@produceState
                                }
                                value = withContext(Dispatchers.Default) {
                                    decodeFilmstripThumb(path)
                                }?.also {
                                    filmstripThumbCache[path] = it
                                    enforceCacheBudgets()
                                }
                            }
                            if (thumbBitmap != null) {
                                Image(
                                    bitmap = thumbBitmap!!,
                                    contentDescription = contentDescription,
                                    contentScale = ContentScale.Crop,
                                    modifier = thumbModifier,
                                )
                            } else {
                                Box(
                                    modifier = thumbModifier.background(
                                        MaterialTheme.colorScheme.surfaceVariant,
                                    ),
                                )
                            }
                        },
                        optionItem = { spec, selected ->
                            val label = spec.type.label()
                            EditorOptionItem(
                                icon = spec.type.iconPainter(),
                                contentDescription = label,
                                label = label,
                                selected = selected,
                            )
                        },
                        colorOption = { optionModifier, mark, onColor ->
                            TextColorOption(
                                currentColor = mark.textColor,
                                customText = colorDraft,
                                modifier = optionModifier,
                                showCustomPicker = true,
                                showCustomInput = false,
                                onColorSelected = onColor,
                                onCustomTextChange = { colorDraft = it },
                            )
                        },
                        iconOption = { optionModifier, _, _ ->
                            IconWatermarkOption(
                                hasIcon = iconBitmap != null,
                                pickLabel = stringResource(Res.string.action_pick),
                                modifier = optionModifier,
                                enabled = !isBusy,
                                onPick = onPickIcon,
                            )
                        },
                        onBack = {
                            // Session NavigateBack owns Launch; clear optimistic shell flag.
                            showEditor = false
                            services.session.onBackPressed()
                        },
                        onAddMoreImages = onPickPhoto,
                        onShowSaveDialog = {
                            // C2: open shared Android Compose export panel (not immediate Photos write).
                            services.session.resetJobStatus()
                            sheetExportFinished = false
                            showSaveSheet = true
                        },
                        onGoAboutScreen = { openAboutFromEditor() },
                        onImageSelected = { info ->
                            // Android parity: select instantly; show placeholder; watermark async + cache.
                            scope.launch {
                                val path = info.uri.value
                                val switchBench = IosPreviewBench.scope("switch_image")
                                try {
                                    services.session.dispatchAndAwait(AppIntent.SelectCurrent(info.uri))
                                    switchBench.mark("select")

                                    // 1) Instant watermarked cache hit
                                    wmPreviewCache[path]?.let { cached ->
                                        previewBitmap = cached
                                        previewSourcePath = path
                                        switchBench.finish(
                                            mapOf(
                                                "hit" to "wm",
                                                "path" to path.substringAfterLast('/'),
                                            ),
                                        )
                                        return@launch
                                    }

                                    // 2) Instant source placeholder (no watermark) while raster runs
                                    val placeholder = sourcePlaceholderCache[path]
                                        ?: withContext(Dispatchers.Default) {
                                            IosPreviewRaster.decodeSourcePlaceholder(path)
                                        }?.also { sourcePlaceholderCache[path] = it }
                                    switchBench.mark("placeholder")
                                    if (placeholder != null) {
                                        previewBitmap = placeholder
                                        previewSourcePath = path
                                    }

                                    // 3) Full watermarked preview (in-memory, no PNG encode)
                                    previewGen += 1
                                    val gen = previewGen
                                    renderPreviewForCurrentSelection(gen = gen)
                                    switchBench.finish(
                                        mapOf(
                                            "hit" to "miss",
                                            "path" to path.substringAfterLast('/'),
                                        ),
                                    )
                                } catch (t: Throwable) {
                                    switchBench.finish(mapOf("error" to (t.message ?: "fail")))
                                    statusLine = "Failed: ${t.message}"
                                }
                            }
                        },
                        onConfigChange = { change ->
                            // F2: typed WatermarkConfigChange from shared controls (no from()).
                            if (isBusy) return@EditorScreen
                            if (change is WatermarkConfigChange.Icon) {
                                onPickIcon()
                                return@EditorScreen
                            }
                            scope.launch {
                                // Config change invalidates watermarked cache (not source placeholders).
                                wmPreviewCache.clear()
                                previewGen += 1
                                val gen = previewGen
                                isBusy = true
                                try {
                                    services.session.dispatchAndAwait(
                                        AppIntent.ApplyConfig(change),
                                    )
                                    renderPreviewForCurrentSelection(gen = gen)
                                } catch (t: Throwable) {
                                    statusLine = "Failed: ${t.message}"
                                }
                                isBusy = false
                            }
                        },
                        onUseTemplate = { template ->
                            val content = template.content ?: return@EditorScreen
                            scope.launch {
                                isBusy = true
                                try {
                                    services.session.dispatchAndAwait(
                                        AppIntent.ApplyConfig(WatermarkConfigChange.Text(content)),
                                    )
                                    reexportCurrent()
                                } catch (t: Throwable) {
                                    statusLine = "Failed: ${t.message}"
                                }
                                isBusy = false
                            }
                        },
                        onAddTemplate = { text ->
                            scope.launch {
                                try {
                                    templateEditor.add(text)
                                } catch (t: Throwable) {
                                    statusLine = "Could not save template: ${t.message}"
                                }
                            }
                        },
                        onUpdateTemplate = { template ->
                            scope.launch {
                                try {
                                    templateEditor.update(template)
                                } catch (t: Throwable) {
                                    statusLine = "Could not update template: ${t.message}"
                                }
                            }
                        },
                        onDeleteTemplate = { template ->
                            scope.launch {
                                try {
                                    templateEditor.delete(template)
                                } catch (t: Throwable) {
                                    statusLine = "Could not delete template: ${t.message}"
                                }
                            }
                        },
                        modifier = Modifier.fillMaxSize(),
                    )
                }
                } // when (route)
            } // ProductShellHost

            if (showOpenSource) {
                OpenSourceScreen(
                    onBack = { showOpenSource = false },
                    onOpenLink = onOpenUrl,
                    backIcon = backPainter,
                    modifier = Modifier.fillMaxSize(),
                )
            }

            // C2: shared export panel (Android Compose parity). Photos write + Share are Swift edges.
            if (showSaveSheet) {
                val exportItems: List<ImageInfo> = sessionImages
                // Always key progress off the live selection size (not stale exportJob.totalCount=0).
                val exportTotal = exportItems.size.coerceAtLeast(
                    if (previewBitmap != null && exportItems.isEmpty()) 1 else 0,
                )
                // Re-read job ticks so thumbnails recompose during batch export (jobState is mutated
                // on ImageInfo; ExportJobState Flow is the recomposition signal).
                val finished = sheetExportFinished || exportJob.isFinished
                val exporting = isSaving || exportJob.isSaving
                val recovery = me.rosuh.easywatermark.ui.save.ExportRecoveryUi.fromJob(
                    isSaving = exporting,
                    isFinished = finished,
                    successCount = exportJob.successCount.coerceAtLeast(exportJob.completedCount),
                    failureCount = exportJob.failureCount,
                    processedCount = exportJob.processedCount
                        .coerceAtLeast(exportJob.successCount + exportJob.failureCount),
                    totalCount = exportJob.totalCount.takeIf { it > 0 } ?: exportTotal,
                )
                val primaryLabel = when {
                    finished -> stringResource(Res.string.share)
                    exporting -> stringResource(Res.string.dialog_save_exporting)
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
                        recovery.successCount,
                        recovery.failureCount,
                        recovery.totalCount.coerceAtLeast(1),
                    )
                }
                val listItems = exportItems.ifEmpty {
                    if (previewBitmap != null) listOf(ImageInfo(MediaRef("preview"))) else emptyList()
                }
                val runIosExportBatch: () -> Unit = {
                    scope.launch {
                        isSaving = true
                        sheetExportFinished = false
                        statusLine = "Saving…"
                        try {
                            val images = exportItems.ifEmpty {
                                error("Nothing to export")
                            }
                            // Batch pipeline: per-item JobState.Ing → Success + exportJob ticks.
                            withContext(Dispatchers.Default) {
                                services.session.exportAndAwait(images)
                            }
                            // D4: await Photos per render success before counting persisted.
                            val lastPath = images
                                .asReversed()
                                .firstOrNull {
                                    it.jobState is JobState.Success &&
                                        (it.result?.data as? MediaRef)?.value != null
                                }
                                ?.let { (it.result?.data as? MediaRef)?.value }
                            val photosResult = persistRenderSuccessesToPhotos(
                                images = images,
                                loadBytes = { path ->
                                    NSData.dataWithContentsOfFile(path)
                                        ?.let { IosByteArrayInterop.fromNSData(it) }
                                },
                                photosSave = photosSaveEdge,
                            )
                            outputPath = lastPath
                            sheetExportFinished = true
                            isSaving = false
                            statusLine = photosPersistStatusLine(
                                batchSize = images.size,
                                result = photosResult,
                            )
                        } catch (t: Throwable) {
                            statusLine = "Export failed: ${t.message}"
                            isSaving = false
                            sheetExportFinished = true
                        }
                    }
                }
                SaveExportSheetShell(
                    items = listItems,
                    selectedFormat = outputFormat,
                    quality = outputQuality,
                    primaryActionLabel = primaryLabel,
                    primaryActionEnabled = when {
                        finished -> true
                        else -> !exporting && !isBusy
                    },
                    // iOS has no in-app gallery; after save, primary becomes Share (E09/E10).
                    showOpenGallery = false,
                    exportListSubtitle = resultSummaryText,
                    imageCount = exportTotal,
                    isExporting = recovery.isExporting,
                    showCancelButton = recovery.showCancel,
                    onCancelClick = { services.session.cancelExport() },
                    showRetryFailedButton = recovery.showRetryFailed,
                    onRetryFailedClick = { runIosExportBatch() },
                    statusContentDescription = statusCd,
                    itemKey = { it.uri.value },
                    onDismiss = {
                        if (!exporting) showSaveSheet = false
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
                        if (finished) {
                            val path = outputPath
                                ?: exportItems.firstNotNullOfOrNull { info ->
                                    (info.result?.data as? MediaRef)?.value
                                }
                            if (path != null) onShare(path)
                        } else {
                            runIosExportBatch()
                        }
                    },
                    onOpenGalleryClick = {},
                ) { info, thumbModifier ->
                    // Per-source-path thumb off-main (never shared previewBitmap; never sync decode).
                    // Sync remember{decode} froze the export LazyRow fling on first open.
                    val path = info.uri.value
                    val cached = exportThumbCache[path]
                    val thumb by produceState(initialValue = cached, path) {
                        if (path.isBlank() || path == "preview") {
                            value = previewBitmap
                            return@produceState
                        }
                        exportThumbCache[path]?.let {
                            value = it
                            return@produceState
                        }
                        value = withContext(Dispatchers.Default) {
                            val data = NSData.dataWithContentsOfFile(path) ?: return@withContext null
                            IosImageDecoder.decodeThumbnail(
                                IosByteArrayInterop.fromNSData(data),
                                maxEdgePx = 96,
                            )
                        }?.also {
                            exportThumbCache[path] = it
                            enforceCacheBudgets()
                        }
                    }
                    val displayThumb = thumb
                        ?: previewBitmap.takeIf { path == "preview" || path.isBlank() }
                    // exportJob in key forces recompose when batch progress advances.
                    val job = remember(
                        info.uri,
                        exportJob.completedCount,
                        exportJob.isSaving,
                        exportJob.isFinished,
                    ) {
                        info.jobState
                    }
                    me.rosuh.easywatermark.ui.save.ExportProgressOverlay(
                        jobState = job,
                        modifier = thumbModifier,
                    ) {
                        if (displayThumb != null) {
                            Image(
                                bitmap = displayThumb,
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

    /**
     * Legacy optimistic shell only — does **not** stage or retain multi full-res owners.
     * Production path is [deliverPickedPhotosBatch] (file-first).
     */
    fun deliverPickedPhoto(bytes: ByteArray) {
        // G4: do not pin full-res bytes on the host; Session path is the durable owner.
        sourceBytes = null
        showEditor = true
    }

    /**
 * Deliver one picked source photo into the session.
 *
 * **Latency:** always stages + EnterEditor first (filmstrip updates immediately). Watermark
 * Export for the big preview runs only when [renderPreview] is true (use false for multi-pick * non-final items so N photos do not mean N full exports).
 *
 * @param append true to append to the current multi-image selection (add-more / multi-select tail).
 * @param renderPreview when true, run export pipeline for the focused (first) image after staging.
     */
    @Throws(Exception::class)
    suspend fun deliverPickedPhotoAndAwait(
        bytes: ByteArray,
        append: Boolean = false,
        renderPreview: Boolean = true,
        pickGeneration: Long,
    ) {
        deliverPickedPhotosBatch(
            images = listOf(bytes),
            append = append,
            renderPreview = renderPreview,
            pickGeneration = pickGeneration,
        )
    }

    /**
 * Navigate to the editor shell **immediately** (before any photo bytes are ready).
 * Call from Swift as soon as the picker dismisses so the user is not blocked on
 * `loadTransferable` / decode / stage. No "Loading…" chrome.
     */
    fun showEditorShellImmediately() {
        // Presentation-only optimistic shell; Session EnterEditor lands when stage succeeds.
        showEditor = true
        // Leave preview blank (silent) until stage + placeholder / raster fill it.
        statusLine = ""
    }

    /**
 * Stage all [images] in **one** EnterEditor (filmstrip fills at once), prefetch filmstrip
 * Thumbs so fling is cold-miss free, then optionally raster the focused preview. *
 * Prefer [showEditorShellImmediately] first so UI is not gated on photo IO.
 * Swift should load **all** picker payloads then call this once (not per-item append).
     */
    /**
     * Stage + bind preview for a picker batch.
     *
     * @param pickGeneration token from [me.rosuh.easywatermark.session.IosPickGenerationGate.nextPhotoGeneration].
     * Session publication is generation-guarded (F12) inside [IosAppServices.stagePickedImagesBytes].
     */
    @Throws(Exception::class)
    suspend fun deliverPickedPhotosBatch(
        images: List<ByteArray>,
        append: Boolean = false,
        renderPreview: Boolean = true,
        pickGeneration: Long,
    ) {
        require(images.isNotEmpty()) { "deliverPickedPhotosBatch: empty" }
        // F11/F16: do not clear host caches until Session publish succeeds for this generation.
        // Superseded picks throw StalePickGenerationException without leaving Session/cache as A.
        withContext(Dispatchers.Default) {
            services.stagePickedImagesBytes(
                imageBytesList = images,
                append = append,
                pickGeneration = pickGeneration,
            )
        }
        // Abort host UI bind if generation flipped after Session publish returned.
        if (!me.rosuh.easywatermark.session.IosPickGenerationGate.isPhotoCurrent(pickGeneration)) {
            return
        }
        if (disposed) return
        // G4 file-first: drop any host full-res source pin; staged paths + Session own identity.
        // Caller's [images] list is stack-scoped and must not be stored on the host.
        sourceBytes = null
        // Session already EnterEditor via stagePickedImagesBytes; keep optimistic flag for isInEditor.
        showEditor = true
        if (!append) {
            wmPreviewCache.clear()
            sourcePlaceholderCache.clear()
            filmstripThumbCache.clear()
            exportThumbCache.clear()
            filmstripThumbEpoch += 1
            previewBitmap = null
            previewSourcePath = null
            ownedStagedPaths.clear()
        }
        statusLine = ""

        val launch = services.session.launchScreenUiStateFlow.first()
        val paths = launch.selectedImageList.map { it.uri.value }.filter { it.isNotBlank() }
        // Track app-owned staged sources for dispose cleanup (E2).
        paths.filter { it.contains("ewm_src_") }.forEach { ownedStagedPaths.add(it) }
        val focusPath = (launch.curImageInfo ?: launch.selectedImageList.firstOrNull())?.uri?.value

        if (renderPreview && focusPath != null) {
            val cached = sourcePlaceholderCache[focusPath]
            val placeholder = cached ?: withContext(Dispatchers.Default) {
                IosPreviewRaster.decodeSourcePlaceholder(focusPath)
            }
            // F16: re-validate after decode suspension before any host cache/preview write.
            me.rosuh.easywatermark.session.IosPickPublishProbe
                .awaitBeforeHostPreviewBind(pickGeneration)
            if (!me.rosuh.easywatermark.session.IosPickGenerationGate.isPhotoCurrent(pickGeneration)) {
                return
            }
            if (placeholder != null) {
                if (cached == null) {
                    sourcePlaceholderCache[focusPath] = placeholder
                    enforceCacheBudgets()
                }
                previewBitmap = placeholder
                previewSourcePath = focusPath
            }
        }

        if (!me.rosuh.easywatermark.session.IosPickGenerationGate.isPhotoCurrent(pickGeneration)) {
            return
        }
        val filmstripPaths = paths
        val filmstripPickGen = pickGeneration
        hostScope.launch {
            try {
                prefetchFilmstripThumbs(filmstripPaths, filmstripPickGen)
            } catch (_: Throwable) {
            }
        }

        if (!renderPreview) {
            return
        }
        if (!me.rosuh.easywatermark.session.IosPickGenerationGate.isPhotoCurrent(pickGeneration)) {
            return
        }
        previewGen += 1
        val gen = previewGen
        hostScope.launch {
            try {
                if (!me.rosuh.easywatermark.session.IosPickGenerationGate.isPhotoCurrent(pickGeneration)) {
                    return@launch
                }
                renderPreviewForCurrentSelection(gen = gen)
            } catch (t: Throwable) {
                statusLine = "Preview failed: ${t.message}"
            }
        }
    }

    private fun decodeFilmstripThumb(path: String): ImageBitmap? {
        val data = NSData.dataWithContentsOfFile(path) ?: return null
        return IosImageDecoder.decodeThumbnail(
            IosByteArrayInterop.fromNSData(data),
            maxEdgePx = 96,
        )
    }

    /**
     * Decode missing filmstrip thumbs off-main; bumps [filmstripThumbEpoch] once when done.
     * [pickGeneration] gates every cache write after suspension (F16).
     */
    private suspend fun prefetchFilmstripThumbs(paths: List<String>, pickGeneration: Long) {
        if (paths.isEmpty()) return
        if (!me.rosuh.easywatermark.session.IosPickGenerationGate.isPhotoCurrent(pickGeneration)) {
            return
        }
        val missing = paths.filter { it.isNotBlank() && !filmstripThumbCache.containsKey(it) }
        if (missing.isEmpty()) return
        // G4: bound concurrent filmstrip decodes to the same ceiling as stage concurrency.
        val gate = Semaphore(IOS_STAGING_MAX_CONCURRENCY)
        val decoded = coroutineScope {
            missing.map { path ->
                async(Dispatchers.Default) {
                    gate.withPermit {
                        path to runCatching { decodeFilmstripThumb(path) }.getOrNull()
                    }
                }
            }.awaitAll()
        }
        if (!me.rosuh.easywatermark.session.IosPickGenerationGate.isPhotoCurrent(pickGeneration)) {
            return
        }
        for ((path, thumb) in decoded) {
            if (thumb != null) filmstripThumbCache[path] = thumb
        }
        enforceCacheBudgets()
        withContext(Dispatchers.Main) {
            if (me.rosuh.easywatermark.session.IosPickGenerationGate.isPhotoCurrent(pickGeneration)) {
                filmstripThumbEpoch += 1
            }
        }
    }

    /**
     * @param pickGeneration icon generation from [me.rosuh.easywatermark.session.IosPickGenerationGate.nextIconGeneration]
     * (F15/F16 — Kotlin publication boundary for icon config via [WatermarkSessionViewModel.applyConfigIf]).
     */
    @Throws(Exception::class)
    suspend fun deliverIconBytesAndAwait(bytes: ByteArray, pickGeneration: Long) {
        isBusy = true
        try {
            if (!me.rosuh.easywatermark.session.IosPickGenerationGate.isIconCurrent(pickGeneration)) {
                throw me.rosuh.easywatermark.session.StalePickGenerationException(pickGeneration)
            }
            val previousRef = services.waterMarkRepo.waterMark.first().iconUri
            val path = IosIconPersistence.writeIconBytes(bytes)
            // Re-check after IO before config publication (F15/F16).
            me.rosuh.easywatermark.session.IosPickPublishProbe.awaitBeforeIconConfig(pickGeneration)
            if (!me.rosuh.easywatermark.session.IosPickGenerationGate.isIconCurrent(pickGeneration)) {
                me.rosuh.easywatermark.data.repo.IosIconPersistence.deleteIfOwned(path)
                throw me.rosuh.easywatermark.session.StalePickGenerationException(pickGeneration)
            }
            val applied = services.session.applyConfigIf(
                stillValid = {
                    me.rosuh.easywatermark.session.IosPickGenerationGate.isIconCurrent(pickGeneration)
                },
                change = WatermarkConfigChange.Icon(MediaRef(path)),
            )
            if (!applied) {
                me.rosuh.easywatermark.data.repo.IosIconPersistence.deleteIfOwned(path)
                throw me.rosuh.easywatermark.session.StalePickGenerationException(pickGeneration)
            }
            // Host-side bind only when generation is still current after config write.
            if (!me.rosuh.easywatermark.session.IosPickGenerationGate.isIconCurrent(pickGeneration)) {
                return
            }
            IosIconPersistence.deleteIfOwned(previousRef.value)
            iconBytes = bytes
            wmPreviewCache.clear()
            previewGen += 1
            renderPreviewForCurrentSelection(gen = previewGen)
        } finally {
            isBusy = false
        }
    }

    /**
     * Legacy secondary status hook (single-item fire-and-forget paths).
     * Production batch export awaits Photos via [onSaveToPhotos] and does not rely on this
     * for batch counts (D4).
     */
    fun markSavedToPhotos(success: Boolean, message: String? = null) {
        isSaving = false
        if (success) {
            sheetExportFinished = true
            statusLine = "Saved to Photos"
        } else {
            statusLine = message ?: "Save failed"
        }
    }

    /**
 * Fast in-memory preview (Android WaterMarkCanvas analogue):
 * - [IosPreviewRaster]: decode+scale+compose ImageBitmap, **no PNG encode/disk**
 * - [wmPreviewCache] hit → 0 raster work
 * - [gen] drops stale async results on rapid filmstrip taps
     */
    private suspend fun renderPreviewForCurrentSelection(
        gen: Int,
        draftOffset: Pair<Float, Float>? = null,
    ) {
        // H0.1: host-level stages around IosPreviewRaster (read/decode/compose logged there too).
        val isDraft = draftOffset != null
        val hostBench = ClampDragBench.previewScope(
            if (isDraft) "ios_draft_preview" else "ios_preview_refresh",
        )
        val launch = services.session.launchScreenUiStateFlow.first()
        val cur = launch.curImageInfo ?: launch.selectedImageList.firstOrNull() ?: return
        val sourcePath = cur.uri.value
        if (sourcePath.isBlank()) return
        val wm = services.waterMarkRepo.waterMark.first()
        val ox = draftOffset?.first ?: cur.offsetX
        val oy = draftOffset?.second ?: cur.offsetY
        hostBench.mark("sessionRead")

        // Cache hit only for committed (non-draft) paints at exact Session offset.
        if (!isDraft) {
            wmPreviewCache[sourcePath]?.let { cached ->
                if (gen != previewGen) return
                previewBitmap = cached
                previewSourcePath = sourcePath
                hostBench.mark("cacheHit")
                hostBench.finish(
                    mapOf(
                        "hit" to true,
                        "path" to sourcePath.substringAfterLast('/'),
                        "offsetX" to ox,
                        "offsetY" to oy,
                    ),
                )
                return
            }
        }

        val composed = withContext(Dispatchers.Default) {
            IosPreviewRaster.renderWatermarked(
                sourcePath = sourcePath,
                waterMark = wm,
                offsetX = ox,
                offsetY = oy,
            )
        }
        hostBench.mark("raster")
        if (gen != previewGen) {
            hostBench.finish(mapOf("staleGen" to true, "hit" to false, "isDraft" to isDraft))
            return
        }

        // Never cache draft bitmaps as committed path entries (export must not see draft paint).
        if (!isDraft) {
            wmPreviewCache[sourcePath] = composed
            enforceCacheBudgets()
            hostBench.mark("cachePut")
        }
        previewBitmap = composed
        previewSourcePath = sourcePath
        hostBench.finish(
            mapOf(
                "hit" to false,
                "isDraft" to isDraft,
                "path" to sourcePath.substringAfterLast('/'),
                "w" to composed.width,
                "h" to composed.height,
                "offsetX" to ox,
                "offsetY" to oy,
            ),
        )
    }

    private suspend fun reexportCurrent() {
        renderPreviewForCurrentSelection(gen = previewGen)
    }

}

// About link edges (match Android ComposeMainActivity ABOUT_URL_*).
private const val ABOUT_URL_RELEASES = "https://github.com/rosuH/EasyWatermark/releases/"
private const val ABOUT_URL_RATE_IOS = "https://apps.apple.com/search?term=Easy%20Watermark"
private const val ABOUT_URL_ISSUES = "https://github.com/rosuH/EasyWatermark/issues/new"
private const val ABOUT_URL_PRIVACY_ZH =
    "https://github.com/rosuH/EasyWatermark/blob/master/PrivacyPolicy_zh-CN.md"
private const val ABOUT_URL_PRIVACY_EN =
    "https://github.com/rosuH/EasyWatermark/blob/master/PrivacyPolicy.md"
private const val ABOUT_URL_DEV = "https://github.com/rosuH"
private const val ABOUT_URL_DESIGNER = "https://tovi.fun/"

/**
 * Sticky About "Force Dynamic Color" preference on iOS.
 * Material You is Android-only; this keeps the switch interactive/persistent for parity.
 */
private object IosDynamicColorPrefs {
    private const val KEY = "sp_force_dynamic_color_ios"

    fun isForced(): Boolean =
        NSUserDefaults.standardUserDefaults.boolForKey(KEY)

    fun setForced(enabled: Boolean) {
        NSUserDefaults.standardUserDefaults.setBool(enabled, forKey = KEY)
    }
}
