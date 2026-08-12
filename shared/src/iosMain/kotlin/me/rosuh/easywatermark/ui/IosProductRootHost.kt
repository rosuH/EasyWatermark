package me.rosuh.easywatermark.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.ComposeUIViewController
import kotlinx.coroutines.CoroutineExceptionHandler
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
import me.rosuh.easywatermark.data.model.toUiProjection
import me.rosuh.easywatermark.data.repo.IosIconPersistence
import me.rosuh.easywatermark.data.repo.TemplateRepository
import me.rosuh.easywatermark.domain.OutputPrefsEditor
import me.rosuh.easywatermark.domain.TemplateEditor
import androidx.compose.ui.graphics.ImageBitmap
import me.rosuh.easywatermark.render.IosByteArrayInterop
import me.rosuh.easywatermark.render.IosImageDecoder
import me.rosuh.easywatermark.render.IosPreviewImageRepository
import me.rosuh.easywatermark.render.IosPreviewKey
import me.rosuh.easywatermark.render.IosPreviewBench
import me.rosuh.easywatermark.render.IosPreviewPurpose
import me.rosuh.easywatermark.render.IosPreviewRaster
import me.rosuh.easywatermark.render.PreviewResolutionPolicy
import me.rosuh.easywatermark.session.AppIntent
import me.rosuh.easywatermark.session.IosAppServices
import me.rosuh.easywatermark.session.IosPickGenerationGate
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
import me.rosuh.easywatermark.shared.generated.resources.dialog_save_destination_photos
import me.rosuh.easywatermark.shared.generated.resources.dialog_save_filename_policy_ios
import me.rosuh.easywatermark.shared.generated.resources.dialog_save_export_counts
import me.rosuh.easywatermark.shared.generated.resources.dialog_save_success_where
import me.rosuh.easywatermark.shared.generated.resources.dialog_save_error_generic
import me.rosuh.easywatermark.shared.generated.resources.share
import me.rosuh.easywatermark.ui.about.AboutDevCard
import me.rosuh.easywatermark.ui.about.AboutScreen
import me.rosuh.easywatermark.ui.about.AboutScreenIcons
import me.rosuh.easywatermark.ui.about.OpenSourceScreen
import me.rosuh.easywatermark.ui.EditorLayoutClass
import me.rosuh.easywatermark.ui.editorLayoutClass
import me.rosuh.easywatermark.ui.usesLargeScreenDialog
import me.rosuh.easywatermark.ui.compose.IconWatermarkOption
import me.rosuh.easywatermark.ui.compose.TextColorOption
import me.rosuh.easywatermark.ui.compose.formatArgbHexColor
import me.rosuh.easywatermark.ui.save.SaveExportSheetShell
import me.rosuh.easywatermark.platform.platformMotionPolicy
import me.rosuh.easywatermark.ui.theme.AppTheme
import me.rosuh.easywatermark.ui.theme.ContentEditorTheme
import me.rosuh.easywatermark.ui.theme.ContentEditorThemeHost
import me.rosuh.easywatermark.ui.theme.ProvideMotionPolicy
import org.jetbrains.compose.resources.stringResource
import platform.Foundation.NSData
import platform.Foundation.NSFileManager
import platform.Foundation.NSUserDefaults
import platform.Foundation.dataWithContentsOfFile
import platform.UIKit.UIViewController
import platform.Foundation.NSLock
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
    /**
     * Host-owned scope for background stage/preview work (not GlobalScope).
     * SupervisorJob alone does **not** swallow child failures: without a
     * [CoroutineExceptionHandler], uncaught throws become K/N process abort
     * (`terminateWithUnhandledException` / SIGABRT on iOS). Log + surface
     * instead of killing the app.
     */
    private val hostScope = CoroutineScope(
        SupervisorJob() +
            Dispatchers.Main +
            CoroutineExceptionHandler { _, t ->
                statusLine = "Background work failed: ${t.message ?: t::class.simpleName}"
                println(
                    "IosProductRootHost uncaught: ${t.message}\n${t.stackTraceToString()}",
                )
            },
    )
    /**
     * App-owned staged source paths (`ewm_src_*`) for this host generation (E2 dispose).
     * Mutated under [lifecycleLock] together with [disposed] so publish→ownership transfer
     * is atomic w.r.t. [dispose].
     */
    private val ownedStagedPaths = linkedSetOf<String>()
    private var disposed = false
    /** Serializes dispose vs post-publish ownership adoption (Main / delivery continuations). */
    private val lifecycleLock = NSLock()

    /** Progressive path-first import (NotificationCenter control plane; zero public API growth). */
    private val progressiveImport = IosProgressiveImportController(
        session = services.session,
        waterMarkProvider = { services.waterMarkRepo.waterMark.first() },
        hostScope = hostScope,
        hostAlive = { !disposed },
        onSlotsChanged = slotsChanged@{ state ->
            // Keep dispose/leave-editor ownership set aligned with progressive Ready paths.
            // Never take lifecycleLock here: progressive may still hold mutationMutex and
            // releaseEditorMediaResources/dispose take lifecycleLock first — avoid deadlock.
            if (disposed) return@slotsChanged
            val readyOwned = state.slots
                .mapNotNull { (it as? EditorMediaSlot.Ready)?.image?.uri?.value }
                .filter { IosSourceStager.isOwnedSourcePath(it) }
            hostScope.launch {
                if (disposed) return@launch
                lifecycleLock.lock()
                try {
                    if (disposed) return@launch
                    val sessionHeld = services.session.launchScreenUiStateFlow.value
                        .selectedImageList
                        .map { it.uri.value }
                        .toSet()
                    val progressiveGone = ownedStagedPaths.filter { prior ->
                        IosSourceStager.isOwnedSourcePath(prior) &&
                            prior !in readyOwned &&
                            prior !in sessionHeld
                    }
                    ownedStagedPaths.removeAll(progressiveGone.toSet())
                    readyOwned.forEach { ownedStagedPaths.add(it) }
                } finally {
                    lifecycleLock.unlock()
                }
            }
        },
        onImportChromeChanged = { inProgress ->
            if (inProgress) {
                markedFirstFilmstripPixels = false
                markedFirstWatermarkedPreview = false
            }
        },
        // Import first Ready only — awaited before Swift firstItemAlone ACK so item-0 paints
        // before item-1 transfer starts. User settle/tap uses [onUserFocusPreview] instead.
        onFocusReadyForPreview = { focusPath ->
            if (!disposed) {
                bindProgressiveFocus(focusPath, ProgressiveFocusBindMode.ImportPriority)
            }
        },
        // User filmstrip settle / tap / remove: Session already selected; light cancelable bind.
        onUserFocusPreview = { focusPath ->
            if (!disposed) {
                bindProgressiveFocus(focusPath, ProgressiveFocusBindMode.UserScroll)
            }
        },
    )

    /**
     * How [bindProgressiveFocus] prioritizes work.
     * - [ImportPriority]: await watermark + focus thumb before return (firstItemAlone ACK gate).
     * - [UserScroll]: cancel via [previewGen]; cache-first paint; never full-strip prefetch.
     */
    private enum class ProgressiveFocusBindMode {
        ImportPriority,
        UserScroll,
    }


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
     * One lifecycle-owned cache/in-flight state machine for source previews, watermarked previews,
     * filmstrip cells and export thumbs. It enforces a joint 40MiB source/preview and 8MiB
     * filmstrip budget; [previewBitmap] below is merely the current visible reference.
     */
    private val previewImages = IosPreviewImageRepository(hostScope)
    /** Current visible bitmap is not a second cache; identity marks whether it is watermarked. */
    private var watermarkedPreviewSourcePath by mutableStateOf<String?>(null)
    /**
     * Invalidation generation for filmstrip thumbs.
     * Bumped only on real invalidation (trim/dispose/bucket change/ownership replace) —
     * **not** after successful prefetch. Prefetch writes the repository cache; visible cells
     * seed from [IosPreviewImageRepository.peekCached] without restarting every produceState.
     */
    private var filmstripThumbEpoch by mutableStateOf(0)
    private var previewGen: Int = 0
    /** Once-per-generation timeline marks for first visible pixels / completed preview. */
    private var markedFirstFilmstripPixels = false
    private var markedFirstWatermarkedPreview = false
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
    /** Last editor layout class for export sheet ≥840 dialog (export host is outside Editor BoxWithConstraints). */
    private var lastEditorLayoutClass by mutableStateOf(EditorLayoutClass.Compact)
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
    private var followPhoto by mutableStateOf(IosContentThemePrefs.isFollowPhoto())
    /** Container-fit committed decode bucket; draft gestures always use the 720px policy bucket. */
    private var committedPreviewBucket by mutableStateOf(PreviewResolutionPolicy.BUCKET_720)
    /**
     * Actual progressive filmstrip cell long-edge pixels from layout measurement.
     * Starts at 0 until the first onSizeChanged; decode falls back to the 128 policy bucket.
     */
    private var measuredFilmstripCellPx by mutableStateOf(0)

    private fun sourcePreviewKey(path: String, bucket: Int = committedPreviewBucket) =
        IosPreviewKey(path, bucket, IosPreviewPurpose.SourcePlaceholder)

    private fun watermarkedPreviewKey(path: String, bucket: Int = committedPreviewBucket) =
        IosPreviewKey(path, bucket, IosPreviewPurpose.Watermarked)

    private fun filmstripBucketPx(): Int =
        PreviewResolutionPolicy.filmstripMaxEdgePx(
            measuredCellPx = measuredFilmstripCellPx.takeIf { it > 0 } ?: 128,
        )

    /** Freeze bucket once per request — same value for cache key and decoder. */
    private fun filmstripKey(path: String, frozenBucketPx: Int = filmstripBucketPx()) =
        IosPreviewKey(
            path,
            frozenBucketPx,
            IosPreviewPurpose.Filmstrip,
        )

    private fun exportThumbnailKey(path: String) =
        IosPreviewKey(path, 96, IosPreviewPurpose.ExportThumbnail)

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
    internal fun previewIdentityForTests(): PreviewIdentitySnapshot {
        val snapshot = previewImages.snapshotForTestsImmediate()
        return PreviewIdentitySnapshot(
            previewSourcePath = previewSourcePath,
            wmCachePaths = snapshot.cachedKeys
                .filter { it.purpose == IosPreviewPurpose.Watermarked }
                .mapTo(linkedSetOf()) { it.ownedPath },
            placeholderCachePaths = snapshot.cachedKeys
                .filter { it.purpose == IosPreviewPurpose.SourcePlaceholder }
                .mapTo(linkedSetOf()) { it.ownedPath },
        )
    }

    /** Test-only: whether [dispose] has completed at least once. */
    internal fun isDisposedForTests(): Boolean = disposed

    /** Test-only: paths still tracked as host-owned staged sources. */
    internal fun ownedStagedPathsForTests(): Set<String> = ownedStagedPaths.toSet()

    /** Test-only: register an app-owned staged path without a full picker deliver. */
    internal fun trackOwnedStagedPathForTests(path: String) {
        if (path.isNotBlank()) ownedStagedPaths.add(path)
    }

    /** Test-only: entry counts + approximate byte totals for budgeted host image caches. */
    internal data class CacheBudgetSnapshot(
        val wmPreview: Int,
        val placeholder: Int,
        val filmstrip: Int,
        val exportThumb: Int,
        val holdsSourceBytes: Boolean,
        val wmPreviewBytes: Long = 0,
        val placeholderBytes: Long = 0,
        val filmstripBytes: Long = 0,
        val exportThumbBytes: Long = 0,
    )

    internal fun cacheBudgetForTests(): CacheBudgetSnapshot {
        val snapshot = previewImages.snapshotForTestsImmediate()
        return CacheBudgetSnapshot(
            wmPreview = snapshot.watermarkedEntries,
            placeholder = snapshot.sourcePlaceholderEntries,
            filmstrip = snapshot.filmstripEntries,
            exportThumb = snapshot.exportThumbnailEntries,
            holdsSourceBytes = sourceBytes != null,
            wmPreviewBytes = snapshot.watermarkedBytes,
            placeholderBytes = snapshot.sourcePlaceholderBytes,
            filmstripBytes = snapshot.filmstripBytes,
            exportThumbBytes = snapshot.exportThumbnailBytes,
        )
    }

    /** Test-only: insert a placeholder cache entry and enforce budgets (no Session change). */
    internal fun putPlaceholderForTests(path: String, bitmap: ImageBitmap) {
        previewImages.putForTestsImmediate(
            IosPreviewKey(path, 720, IosPreviewPurpose.SourcePlaceholder),
            bitmap,
        )
    }

    /** Test-only: insert a wm preview cache entry and enforce budgets. */
    internal fun putWmPreviewForTests(path: String, bitmap: ImageBitmap) {
        previewImages.putForTestsImmediate(
            IosPreviewKey(path, 720, IosPreviewPurpose.Watermarked),
            bitmap,
        )
    }

    /** Test-only: insert a filmstrip thumb and enforce budgets. */
    internal fun putFilmstripThumbForTests(path: String, bitmap: ImageBitmap) {
        previewImages.putForTestsImmediate(
            IosPreviewKey(path, 96, IosPreviewPurpose.Filmstrip),
            bitmap,
        )
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
        watermarkedPreviewSourcePath = null
        previewGen += 1
        filmstripThumbEpoch += 1
        previewImages.clearFromOwner()
    }

    /** Alias for Swift / ObjC memory-warning bridge. */
    fun onMemoryWarning() = trimCaches()

    /**
     * Leave-editor media lifecycle: drop presentation bitmaps, bounded preview caches, and
     * app-owned staged source files that Session no longer holds.
     *
     * Call after Session [AppIntent.NavigateBack] (or any path that empties selection while the
     * host stays alive). Idempotent. Does not cancel export (caller owns that) and does not
     * [dispose] the host.
     */
    fun releaseEditorMediaResources() {
        if (disposed) return
        lifecycleLock.lock()
        try {
            if (disposed) return
            sourceBytes = null
            previewBitmap = null
            previewSourcePath = null
            watermarkedPreviewSourcePath = null
            outputPath = null
            previewGen += 1
            filmstripThumbEpoch += 1
            previewImages.clearFromOwner()
            progressiveImport.releaseUnheldSourcesAfterLeaveEditor()
            val sessionHeld = services.session.launchScreenUiStateFlow.value.selectedImageList
                .map { it.uri.value }
                .filter { it.isNotBlank() }
                .toSet()
            val toDelete = ownedStagedPaths.filter {
                IosSourceStager.isOwnedSourcePath(it) && it !in sessionHeld
            }
            ownedStagedPaths.removeAll(toDelete.toSet())
            toDelete.forEach(IosSourceStager::deleteQuietly)
        } finally {
            lifecycleLock.unlock()
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
        lifecycleLock.lock()
        try {
            if (disposed) return
            disposed = true
            // Tear down NC observers first so process-wide NotificationCenter cannot deliver into
            // a dead host (suite isolation + single-scene rebuild safety).
            progressiveImport.close()
            services.session.cancelExport()
            previewGen += 1
            // Synchronously mark the preview repository closed when uncontended *before*
            // cancelling host children — otherwise a contended close coroutine can be aborted
            // before writing closed=true.
            previewImages.closeFromOwner()
            hostScope.coroutineContext.cancelChildren()
            // Clear presentation + caches (Main-thread host; lock serializes vs ownership adopt).
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
            watermarkedPreviewSourcePath = null
            // Process-wide Session may still reference these ewm_src paths after Host rebuild.
            // Only delete host-tracked temps that Session no longer holds.
            val sessionHeld = services.session.launchScreenUiStateFlow.value.selectedImageList
                .map { it.uri.value }
                .filter { it.isNotBlank() }
                .toSet()
            val toDelete = ownedStagedPaths.toList()
            ownedStagedPaths.clear()
            toDelete.forEach { path ->
                if (path.contains("ewm_src_") && path !in sessionHeld) {
                    IosSourceStager.deleteQuietly(path)
                }
            }
        } finally {
            lifecycleLock.unlock()
        }
    }

    init {
        progressiveImport.installObservers()
    }

    companion object {
        /** G4: watermarked preview cache entry cap (secondary safety). */
        const val WM_PREVIEW_CACHE_MAX: Int = 8
        /** G4: source-only placeholder cache entry cap. */
        const val PLACEHOLDER_CACHE_MAX: Int = 12
        /** G4: filmstrip thumb cache entry cap. */
        const val FILMSTRIP_THUMB_CACHE_MAX: Int = 48
        /** G4: export-sheet thumb cache entry cap. */
        const val EXPORT_THUMB_CACHE_MAX: Int = 48

        /**
         * H2: approximate byte budgets (ARGB_8888 ≈ w×h×4). Engineering defaults —
         * **not** H3 release SLOs / CI hard gates.
         * WM preview max-edge 720 → ~2MB/entry; 8× ≈ 16MB ceiling.
         */
        const val WM_PREVIEW_BYTES_MAX: Long = 16L * 1024 * 1024
        const val PLACEHOLDER_BYTES_MAX: Long = 12L * 1024 * 1024
        /** Filmstrip thumbs ~96px edge → small; keep modest multi-image set. */
        const val FILMSTRIP_THUMB_BYTES_MAX: Long = 8L * 1024 * 1024
        const val EXPORT_THUMB_BYTES_MAX: Long = 8L * 1024 * 1024
    }

    fun viewController(): UIViewController = ComposeUIViewController {
        // ADR-0028: process-wide Coil ImageLoader (path ProductThumb Fetcher).
        me.rosuh.easywatermark.ui.image.installProductImageLoaderFactory()
        AppTheme(darkTheme = true) {
            // I3: UIAccessibility reduce motion → MotionPolicy.Reduced when enabled.
            ProvideMotionPolicy(platformMotionPolicy()) {
            val waterMark by services.waterMarkRepo.waterMark.collectAsState(WaterMark.default)
            val launchUi by services.session.launchScreenUiStateFlow.collectAsState()
            // exportJobState is collected only while the save sheet is open (see showSaveSheet
            // block) so export ticks do not recompose the entire Editor / filmstrip tree.
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
            // Leave-editor lifecycle closed loop: Session emptied (back, last-image remove, etc.)
            // while Host stays alive — free preview caches + app-owned ewm_src temps.
            LaunchedEffect(launchUi.uiState, sessionImages.size) {
                if (
                    launchUi.uiState == LaunchScreenUiState.Launch &&
                    sessionImages.isEmpty() &&
                    !disposed
                ) {
                    showEditor = false
                    releaseEditorMediaResources()
                }
            }
            LaunchedEffect(Unit) {
                try {
                    services.userConfigRepo.userPreferences.first().let {
                        outputFormat = it.outputFormat
                        outputQuality = it.compressLevel
                    }
                } catch (t: Throwable) {
                    statusLine = "Prefs load failed: ${t.message}"
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
                    BoxWithConstraints(Modifier.fillMaxSize()) {
                    val aboutLarge = maxWidth.value >= 840f
                    AboutScreen(
                        versionName = ProductVersion.NAME,
                        showBounds = aboutShowBounds,
                        showFollowWallpaperSwitch = false,
                        followPhotoOn = followPhoto,
                        onToggleFollowPhoto = { enabled ->
                            IosContentThemePrefs.setFollowPhoto(enabled)
                            followPhoto = enabled
                            scope.launch {
                                runCatching { services.userConfigRepo.updateFollowPhoto(enabled) }
                            }
                        },
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
                                previewImages.clearPurposeFromOwner(IosPreviewPurpose.Watermarked)
                                watermarkedPreviewSourcePath = null
                            }
                        },
                        // Production: large hero About logo + gradient animation.
                        logo = { logoModifier ->
                            me.rosuh.easywatermark.ui.AboutPageLogo(
                                modifier = logoModifier,
                                animate = true,
                            )
                        },
                        useLargeLayout = aboutLarge,
                    )
                    } // BoxWithConstraints About
                }

                ProductShellNav.Route.Editor -> {
                    val displayPreview = previewBitmap
                    val iconBitmap = iconBytes?.let { bytes ->
                        remember(bytes) { IosImageDecoder.decode(bytes) }
                    }
                    var colorDraft by remember(waterMark.textColor) {
                        mutableStateOf(formatArgbHexColor(waterMark.textColor))
                    }

                    // I1: container constraints in Dp → pure EditorLayoutClass (no UIKit types in domain).
                    BoxWithConstraints(Modifier.fillMaxSize()) {
                    val layoutClass = remember(maxWidth, maxHeight) {
                        editorLayoutClass(maxWidth.value, maxHeight.value)
                    }
                    lastEditorLayoutClass = layoutClass
                    val density = LocalDensity.current
                    val bucketImage = launchUi.curImageInfo ?: sessionImages.firstOrNull()
                    val requestedPreviewBucket = remember(
                        maxWidth,
                        maxHeight,
                        bucketImage?.uri,
                        bucketImage?.width,
                        bucketImage?.height,
                        density,
                    ) {
                        PreviewResolutionPolicy.committedMaxEdgePxForFit(
                            sourceWidthPx = bucketImage?.width ?: 0,
                            sourceHeightPx = bucketImage?.height ?: 0,
                            containerWidthPx = with(density) { maxWidth.toPx().toInt() },
                            containerHeightPx = with(density) { maxHeight.toPx().toInt() },
                        )
                    }
                    LaunchedEffect(requestedPreviewBucket) {
                        if (committedPreviewBucket != requestedPreviewBucket) {
                            committedPreviewBucket = requestedPreviewBucket
                            val selected = launchUi.curImageInfo ?: sessionImages.firstOrNull()
                            if (selected != null) {
                                previewGen += 1
                                hostScope.launch {
                                    runCatching { renderPreviewForCurrentSelection(previewGen) }
                                }
                            }
                        }
                    }
                    // Pending/Failed are Host-only presentation cells. Supplying them through an
                    // internal CompositionLocal keeps the public EditorScreen/Shared.framework
                    // surface unchanged while the shared chrome can render the fixed strip.
                    val progressiveSlots = progressiveImport.slots
                    val progressivePresentation = progressiveSlots
                        .takeIf { it.slots.isNotEmpty() }
                        ?.let { slotState ->
                            EditorProgressiveSlotPresentation(
                                state = slotState,
                                importInProgress = progressiveImport.importInProgress,
                                onSelectReady = progressiveImport::requestFocusReady,
                                onRetry = progressiveImport::requestRetry,
                                onRemove = progressiveImport::requestRemove,
                                onPrioritize = progressiveImport::requestPrioritize,
                                measuredCellPx = measuredFilmstripCellPx,
                                onCellPxMeasured = { px ->
                                    if (px > 0 && px != measuredFilmstripCellPx) {
                                        val oldBucket = filmstripBucketPx()
                                        measuredFilmstripCellPx = px
                                        val newBucket = filmstripBucketPx()
                                        // Bucket upgrade must not keep stale smaller thumbnails.
                                        if (newBucket != oldBucket) {
                                            filmstripThumbEpoch += 1
                                            me.rosuh.easywatermark.session.ImportTimelineProbe.mark(
                                                "filmstrip_bucket_changed",
                                                progressiveImport.activeGeneration,
                                                "b$oldBucket-b$newBucket",
                                            )
                                        }
                                    }
                                },
                                nowMs = { progressiveImport.nowMonoMsForTests() },
                            )
                        }
                    CompositionLocalProvider(
                        LocalEditorProgressiveSlotPresentation provides progressivePresentation,
                    ) {
                    // ADR-0027/0028: MCU seed via product Coil path (shared max-edge with filmstrip).
                    val themeSeedRef = (launchUi.curImageInfo ?: sessionImages.firstOrNull())?.uri
                        ?: previewSourcePath?.let { MediaRef(it) }
                    val themeSeedKey = themeSeedRef?.value
                    val themeSeedBitmap = me.rosuh.easywatermark.ui.image.rememberProductThumbBitmap(
                        ref = themeSeedRef,
                        maxEdgePx = me.rosuh.easywatermark.ui.image.ProductThumb.UI_THUMB_MAX_EDGE,
                        enabled = followPhoto,
                    )
                    ContentEditorThemeHost(
                        enabled = followPhoto,
                        seedBitmap = themeSeedBitmap,
                        seedKey = themeSeedKey,
                    ) {
                    EditorScreen(
                        imageList = sessionImages.map { it.toUiProjection() },
                        waterMark = waterMark,
                        selectedImage = (launchUi.curImageInfo ?: sessionImages.firstOrNull())
                            ?.toUiProjection(),
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
                                        watermarkedPreviewSourcePath == dragPath ||
                                            draftActiveForSelection
                                        )
                            // M2/M7: policy-aware first reveal + switch fade (iOS was hard-cut).
                            // Ready-frame only: path is set with watermarked/source bind; hasContent
                            // false while displayPreview null so key cannot advance on empty pixels.
                            AnimatedPreviewSurface(
                                contentKey = previewSourcePath,
                                hasContent = displayPreview != null &&
                                    !previewSourcePath.isNullOrEmpty(),
                                modifier = previewModifier
                                    .fillMaxSize()
                                    .testTag("sharedComposeWatermarkPreview"),
                            ) {
                                val bmp = displayPreview
                                if (bmp != null) {
                                    Image(
                                        bitmap = bmp,
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
                                                imageWidth = bmp.width.toFloat(),
                                                imageHeight = bmp.height.toFloat(),
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
                                                        watermarkedPreviewSourcePath != dragPath &&
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
                                                    previewImages.invalidateOwnedPathFromOwner(
                                                        ownedPath = dragPath,
                                                        purpose = IosPreviewPurpose.Watermarked,
                                                    )
                                                    watermarkedPreviewSourcePath = null
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
                            // ADR-0028: product Coil path (IosImageIODecoder via ProductThumb Fetcher).
                            // Shared max-edge with theme seed for memory-cache hits.
                            val path = imageInfo.uri.value
                            if (path.isNotBlank() && path != "preview" && !markedFirstFilmstripPixels) {
                                SideEffect {
                                    if (!markedFirstFilmstripPixels) {
                                        markedFirstFilmstripPixels = true
                                        me.rosuh.easywatermark.session.ImportTimelineProbe.mark(
                                            "first_filmstrip_pixels",
                                            me.rosuh.easywatermark.session.IosPickGenerationGate
                                                .currentPhotoGeneration(),
                                            "cell",
                                        )
                                    }
                                }
                            }
                            me.rosuh.easywatermark.ui.image.ProductAsyncImage(
                                thumb = me.rosuh.easywatermark.ui.image.ProductThumb(
                                    ref = imageInfo.uri,
                                    maxEdgePx = me.rosuh.easywatermark.ui.image.ProductThumb.UI_THUMB_MAX_EDGE,
                                ),
                                contentDescription = contentDescription,
                                contentScale = ContentScale.Crop,
                                modifier = thumbModifier.background(
                                    MaterialTheme.colorScheme.surfaceVariant,
                                ),
                            )
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
                            // Session NavigateBack owns Launch; clear optimistic shell flag
                            // and release staged sources + preview bitmaps (leave-editor lifecycle).
                            showEditor = false
                            services.session.onBackPressed()
                            releaseEditorMediaResources()
                        },
                        onAddMoreImages = onPickPhoto,
                        onShowSaveDialog = {
                            // Block export while progressive import still has Pending/in-flight work
                            // (fresh pick no longer clears Session until first Ready publishes).
                            if (progressiveImport.importInProgress) return@EditorScreen
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
                                val previewBucket = committedPreviewBucket
                                val switchBench = IosPreviewBench.scope("switch_image")
                                try {
                                    services.session.dispatchAndAwait(AppIntent.SelectCurrent(info.uri))
                                    switchBench.mark("select")

                                    // 1) Instant watermarked cache hit
                                    previewImages.cached(watermarkedPreviewKey(path, previewBucket))?.let { cached ->
                                        previewBitmap = cached
                                        previewSourcePath = path
                                        watermarkedPreviewSourcePath = path
                                        switchBench.finish(
                                            mapOf(
                                                "hit" to "wm",
                                                "path" to path.substringAfterLast('/'),
                                            ),
                                        )
                                        return@launch
                                    }

                                    // 2) Instant source placeholder (no watermark) while raster runs
                                    val placeholder = previewImages.load(sourcePreviewKey(path, previewBucket)) {
                                        withContext(Dispatchers.Default) {
                                            IosPreviewRaster.decodeSourcePlaceholder(
                                                path,
                                                maxEdgePx = previewBucket,
                                            )
                                        }
                                    }
                                    switchBench.mark("placeholder")
                                    if (placeholder != null) {
                                        previewBitmap = placeholder
                                        previewSourcePath = path
                                        watermarkedPreviewSourcePath = null
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
                                previewImages.clearPurposeFromOwner(IosPreviewPurpose.Watermarked)
                                watermarkedPreviewSourcePath = null
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
                            // Same invalidate path as onConfigChange: watermarkedPreviewKey is
                            // path+bucket only, so a cache hit would keep the old text raster.
                            scope.launch {
                                isBusy = true
                                try {
                                    previewImages.clearPurposeFromOwner(IosPreviewPurpose.Watermarked)
                                    watermarkedPreviewSourcePath = null
                                    previewGen += 1
                                    val gen = previewGen
                                    services.session.dispatchAndAwait(
                                        AppIntent.ApplyConfig(WatermarkConfigChange.Text(content)),
                                    )
                                    renderPreviewForCurrentSelection(gen = gen)
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
                        layoutClass = layoutClass,
                    )
                    }
                                        } // ContentEditorThemeHost
                    } // BoxWithConstraints Editor
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
                // Collect export job only inside the sheet composition — not at product root —
                // so isSaving/completedCount ticks do not force Editor filmstrip/preview to recompose.
                val exportJob by services.session.exportJobState.collectAsState()
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
                        recovery.processedCount
                            .coerceAtLeast(recovery.successCount + recovery.failureCount),
                        recovery.successCount,
                        recovery.failureCount,
                        recovery.totalCount.coerceAtLeast(1),
                    )
                }
                val destinationLine = stringResource(Res.string.dialog_save_destination_photos)
                val filenamePolicyLine = stringResource(Res.string.dialog_save_filename_policy_ios)
                val exportCountTotal =
                    if (recovery.isExporting || recovery.isFinished) {
                        recovery.totalCount.coerceAtLeast(exportTotal.coerceAtLeast(1))
                    } else {
                        0
                    }
                val exportCountSuccess =
                    if (recovery.isExporting || recovery.isFinished) recovery.successCount else 0
                val exportCountFailure =
                    if (recovery.isExporting || recovery.isFinished) recovery.failureCount else 0
                val countsLine = if (exportCountTotal > 0) {
                    stringResource(
                        Res.string.dialog_save_export_counts,
                        exportCountTotal,
                        exportCountSuccess,
                        exportCountFailure,
                    )
                } else {
                    ""
                }
                // No Saved-to-destination paint; keep generic error as a11y residual only.
                val outcomeDetailLine = when {
                    recovery.isAllFailed ->
                        stringResource(Res.string.dialog_save_error_generic)
                    else -> ""
                }
                val listItems = exportItems.ifEmpty {
                    if (previewBitmap != null) listOf(ImageInfo(MediaRef("preview"))) else emptyList()
                }
                val exportErrorGeneric = stringResource(Res.string.dialog_save_error_generic)
                val exportingLabel = stringResource(Res.string.dialog_save_exporting)
                val runIosExportBatch: () -> Unit = {
                    scope.launch {
                        isSaving = true
                        sheetExportFinished = false
                        statusLine = exportingLabel
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
                            // Restore editor main preview if joint cache pressure blanked it mid-export.
                            ensureEditorPreviewAfterExport()
                        } catch (_: Throwable) {
                            // I0: never surface raw Throwable.message in product chrome.
                            statusLine = exportErrorGeneric
                            isSaving = false
                            sheetExportFinished = true
                            ensureEditorPreviewAfterExport()
                        }
                    }
                }
                SaveExportSheetShell(
                    items = listItems,
                    useLargeDialog = usesLargeScreenDialog(lastEditorLayoutClass),
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
                    destinationLine = destinationLine,
                    filenamePolicyLine = filenamePolicyLine,
                    countsLine = countsLine,
                    outcomeDetailLine = outcomeDetailLine,
                    exportTotalCount = exportCountTotal,
                    exportSuccessCount = exportCountSuccess,
                    exportFailureCount = exportCountFailure,
                    itemKey = { it.uri.value },
                    onDismiss = {
                        if (!exporting) {
                            showSaveSheet = false
                            // If export thumbs evicted the watermarked preview under budget pressure,
                            // rebind so the editor does not stay blank after sheet close.
                            rebindEditorPreviewIfBlank(scope)
                        }
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
                    // ADR-0028: export-sheet chrome thumbs via ProductAsyncImage (not preview compose).
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
                        me.rosuh.easywatermark.ui.image.ProductAsyncImage(
                            thumb = me.rosuh.easywatermark.ui.image.ProductThumb(
                                ref = info.uri,
                                maxEdgePx = me.rosuh.easywatermark.ui.image.ProductThumb.UI_THUMB_MAX_EDGE,
                            ),
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize().background(
                                MaterialTheme.colorScheme.surfaceVariant,
                            ),
                        )
                    }
                }
            }
            } // ProvideMotionPolicy
        } // AppTheme
    } // ComposeUIViewController

    /**
     * Focus-first bind for progressive import and user filmstrip focus.
     *
     * **[ProgressiveFocusBindMode.ImportPriority]** (import first Ready only):
     * 1. Watermark-region placeholder (fast main-canvas paint)
     * 2. Watermarked preview (product-critical; awaited — gates Swift firstItemAlone ACK)
     * 3. Focus filmstrip thumb (item-0 list cell before later transfers)
     * Does **not** full-strip prefetch (visible produceState + batch deliver cover the strip).
     *
     * **[ProgressiveFocusBindMode.UserScroll]** (settle / tap / remove):
     * Session select already completed; this path is cancelable via [previewGen].
     * Cache-hit paints first; no forced placeholder decode; focus thumb only if missing;
     * never full-strip [prefetchFilmstripThumbs].
     */
    private suspend fun bindProgressiveFocus(
        focusPath: String,
        mode: ProgressiveFocusBindMode,
    ) {
        if (disposed || focusPath.isBlank()) return
        val previewBucket = committedPreviewBucket
        val pickGen = IosPickGenerationGate.currentPhotoGeneration()

        // User scroll: already showing the correct watermarked preview — only fill missing thumb.
        if (
            mode == ProgressiveFocusBindMode.UserScroll &&
            watermarkedPreviewSourcePath == focusPath &&
            previewBitmap != null
        ) {
            ensureFocusFilmstripThumb(focusPath, onlyIfMissing = true)
            return
        }

        // User scroll: instant swap from watermarked cache without starting a new raster.
        if (mode == ProgressiveFocusBindMode.UserScroll) {
            previewImages.peekCached(watermarkedPreviewKey(focusPath, previewBucket))?.let { hit ->
                showEditor = true
                previewBitmap = hit
                previewSourcePath = focusPath
                watermarkedPreviewSourcePath = focusPath
                // Still bump gen so any in-flight raster for a prior focus is dropped on publish.
                previewGen += 1
                ensureFocusFilmstripThumb(focusPath, onlyIfMissing = true)
                return
            }
            // Cache-hit placeholder only — never force a placeholder decode on settle (watermark
            // path will decode source once). Miss leaves prior preview until raster completes.
            previewImages.peekCached(sourcePreviewKey(focusPath, previewBucket))?.let { hit ->
                showEditor = true
                previewBitmap = hit
                previewSourcePath = focusPath
                watermarkedPreviewSourcePath = null
            }
        } else {
            // 1) Import: watermark region — placeholder first (may decode).
            val placeholder = runCatching {
                previewImages.load(sourcePreviewKey(focusPath, previewBucket)) {
                    withContext(Dispatchers.Default) {
                        IosPreviewRaster.decodeSourcePlaceholder(
                            focusPath,
                            maxEdgePx = previewBucket,
                        )
                    }
                }
            }.getOrNull()
            if (disposed) return
            showEditor = true
            if (placeholder != null) {
                previewBitmap = placeholder
                previewSourcePath = focusPath
                watermarkedPreviewSourcePath = null
                me.rosuh.easywatermark.session.ImportTimelineProbe.mark(
                    "first_visible_placeholder",
                    pickGen,
                    "focus",
                )
            }
        }

        // 2) Watermarked main preview — cancelable via previewGen on rapid user focus.
        // ImportPriority awaits this before ACK; UserScroll still runs it but never full-strips.
        previewGen += 1
        val gen = previewGen
        try {
            renderPreviewForCurrentSelection(gen = gen)
            if (
                mode == ProgressiveFocusBindMode.ImportPriority &&
                !markedFirstWatermarkedPreview &&
                previewBitmap != null &&
                watermarkedPreviewSourcePath != null
            ) {
                markedFirstWatermarkedPreview = true
                me.rosuh.easywatermark.session.ImportTimelineProbe.mark(
                    "first_watermarked_preview",
                    IosPickGenerationGate.currentPhotoGeneration(),
                    "preview",
                )
            }
        } catch (_: Throwable) {
        }
        if (disposed || gen != previewGen) return

        // 3) Focus filmstrip thumb — import always; user only when cache miss.
        ensureFocusFilmstripThumb(
            focusPath,
            onlyIfMissing = mode == ProgressiveFocusBindMode.UserScroll,
        )
        // 4) Full-strip prefetch intentionally omitted on both modes of this hot path.
        // Batch [deliverPickedPhotosBatch] still prefetches once after stage; visible cells
        // load via produceState. Re-prefetching all selected paths on every focus was the
        // dominant settle hang driver (see jank-repro-20260808-170839).
    }

    /** Decode/load the focused filmstrip cell into the shared repository (visible produceState peers). */
    private suspend fun ensureFocusFilmstripThumb(
        focusPath: String,
        onlyIfMissing: Boolean,
    ) {
        if (disposed || focusPath.isBlank()) return
        try {
            val frozenBucket = filmstripBucketPx()
            val key = filmstripKey(focusPath, frozenBucket)
            if (onlyIfMissing && previewImages.peekCached(key) != null) return
            previewImages.load(key) {
                withContext(Dispatchers.Default) {
                    decodeFilmstripThumb(focusPath, frozenBucket)
                }
            }
        } catch (_: Throwable) {
        }
    }

    /**
     * Legacy optimistic shell only — does **not** stage or retain multi full-res owners.
     * Production path is progressive NotificationCenter import; batch byte delivery remains for fixtures.
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
        // Disposed host must not stage or publish (lifecycle validity joins generation gate).
        if (disposed) return
        // Snapshot prior Session selection + host-owned temps for transactional ownership / revert.
        val previousLaunch = services.session.launchScreenUiStateFlow.value
        val previousSelection = previousLaunch.selectedImageList.toList()
        val previousWaterMark = previousLaunch.waterMark
        val previousOwned = ownedStagedPaths.toList()
        // F11/F16: stage+publish on Default; hostAlive re-checked at guarded publish boundary.
        // Public ObjC API is the 3-arg stagePickedImagesBytes; host uses internal lifecycle gate.
        val published = withContext(Dispatchers.Default) {
            services.stagePickedImagesBytesInternal(
                imageBytesList = images,
                append = append,
                pickGeneration = pickGeneration,
                hostAlive = { !disposed },
            )
        }
        // Test seam: force dispose in the post-publication / pre-ownership-registration window.
        me.rosuh.easywatermark.session.IosHostOwnershipProbe.awaitBeforeAdopt()
        // Identity-scoped adopt: cleanup/revert only when Session still holds this delivery.
        val adopt = adoptPublishedOwnership(
            previousOwned = previousOwned,
            previousSelection = previousSelection,
            previousWaterMark = previousWaterMark,
            append = append,
            deliveryStagedPaths = published.stagedPaths.toSet(),
            pickGeneration = pickGeneration,
            publishedSelectionUris = published.publishedSelectionUris,
        )
        if (!adopt.alive) {
            return
        }
        // G4 file-first: drop any host full-res source pin; staged paths + Session own identity.
        sourceBytes = null
        showEditor = true
        statusLine = ""
        // Abort host UI bind if generation flipped after Session publish returned.
        if (!me.rosuh.easywatermark.session.IosPickGenerationGate.isPhotoCurrent(pickGeneration)) {
            return
        }
        val launch = services.session.launchScreenUiStateFlow.value
        val paths = launch.selectedImageList.map { it.uri.value }.filter { it.isNotBlank() }
        val focusPath = (launch.curImageInfo ?: launch.selectedImageList.firstOrNull())?.uri?.value
        if (renderPreview && focusPath != null) {
            val previewBucket = committedPreviewBucket
            val placeholder = previewImages.load(sourcePreviewKey(focusPath, previewBucket)) {
                withContext(Dispatchers.Default) {
                    IosPreviewRaster.decodeSourcePlaceholder(
                        focusPath,
                        maxEdgePx = previewBucket,
                    )
                }
            }
            // F16: re-validate after decode suspension before any host cache/preview write.
            me.rosuh.easywatermark.session.IosPickPublishProbe
                .awaitBeforeHostPreviewBind(pickGeneration)
            if (!me.rosuh.easywatermark.session.IosPickGenerationGate.isPhotoCurrent(pickGeneration)) {
                return
            }
            if (placeholder != null) {
                previewBitmap = placeholder
                previewSourcePath = focusPath
                watermarkedPreviewSourcePath = null
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

    private data class OwnershipAdoptResult(val alive: Boolean)

    private enum class LateDisposeSessionAction {
        /** Host alive — ownership adopted. */
        Alive,

        /** Host disposed; Session still this delivery; previous paths readable → restore. */
        RevertPrevious,

        /** Host disposed; Session still this delivery; previous dead/empty → leave editor empty. */
        NavigateBack,

        /** Host disposed; a newer generation already owns Session — do not touch Session. */
        LeaveSession,
    }

    /**
     * Atomically w.r.t. [dispose]: adopt Session-published `ewm_src_*` into [ownedStagedPaths],
     * or perform **identity-scoped** late-dispose cleanup for [deliveryStagedPaths] only.
     *
     * Late-dispose rules (attempt 5):
     * - Cleanup deletes only paths staged by **this** delivery (never enumerate process-wide Session
     *   as ownership, and never delete a newer generation's paths).
     * - Session is mutated only when generation is still current **and** Session selection still
     *   equals [publishedSelectionUris] (this delivery still owns Session).
     * - Never restore [previousSelection] when those paths were already deleted by [dispose].
     *
     * Session intents use [dispatchAndAwait] **outside** [lifecycleLock] so dispose cannot
     * deadlock against a suspended Main intent.
     */
    private suspend fun adoptPublishedOwnership(
        previousOwned: List<String>,
        previousSelection: List<me.rosuh.easywatermark.data.model.ImageInfo>,
        previousWaterMark: me.rosuh.easywatermark.data.model.WaterMark,
        append: Boolean,
        deliveryStagedPaths: Set<String>,
        pickGeneration: Long,
        publishedSelectionUris: List<String>,
    ): OwnershipAdoptResult {
        var action = LateDisposeSessionAction.Alive
        lifecycleLock.lock()
        try {
            val launch = services.session.launchScreenUiStateFlow.value
            val sessionUris = launch.selectedImageList.map { it.uri.value }
            val sessionSet = sessionUris.toSet()
            val sessionOwned = sessionUris.filter { it.contains("ewm_src_") }.toSet()
            val genCurrent =
                me.rosuh.easywatermark.session.IosPickGenerationGate.isPhotoCurrent(pickGeneration)
            // Exact selection identity: this delivery still owns the process-wide Session.
            val sessionStillOurs = genCurrent && sessionUris == publishedSelectionUris

            if (disposed) {
                // Identity-scoped temp cleanup: only this delivery's staged paths.
                // If a newer generation already owns Session, keep any of our paths that it still
                // references (unusual but safe); delete the rest.
                val toDelete = if (sessionStillOurs) {
                    deliveryStagedPaths
                } else {
                    deliveryStagedPaths.filter { it !in sessionSet }
                }
                toDelete.forEach { IosSourceStager.deleteQuietly(it) }

                action = if (!sessionStillOurs) {
                    LateDisposeSessionAction.LeaveSession
                } else if (previousSelection.isNotEmpty() && previousSelectionReadable(previousSelection)) {
                    LateDisposeSessionAction.RevertPrevious
                } else {
                    // Dispose may have deleted previous host-owned ewm_src paths — never restore
                    // Session to dead files.
                    LateDisposeSessionAction.NavigateBack
                }
            } else if (!append) {
                previousOwned.filter { it !in sessionOwned }.forEach { IosSourceStager.deleteQuietly(it) }
                ownedStagedPaths.clear()
                previewImages.clearFromOwner()
                filmstripThumbEpoch += 1
                previewBitmap = null
                previewSourcePath = null
                watermarkedPreviewSourcePath = null
                // Track current Session ewm_src paths (replace publishes full selection).
                sessionOwned.forEach { ownedStagedPaths.add(it) }
            } else {
                // Append: own exactly the paths this delivery staged (not process-wide diff).
                deliveryStagedPaths.forEach { ownedStagedPaths.add(it) }
            }
        } finally {
            lifecycleLock.unlock()
        }
        when (action) {
            LateDisposeSessionAction.Alive,
            LateDisposeSessionAction.LeaveSession,
            -> Unit
            LateDisposeSessionAction.RevertPrevious -> {
                services.session.dispatchAndAwait(
                    me.rosuh.easywatermark.session.AppIntent.EnterEditor(
                        selected = previousSelection,
                        waterMark = previousWaterMark,
                    ),
                )
            }
            LateDisposeSessionAction.NavigateBack -> {
                services.session.dispatchAndAwait(
                    me.rosuh.easywatermark.session.AppIntent.NavigateBack,
                )
            }
        }
        return OwnershipAdoptResult(alive = action == LateDisposeSessionAction.Alive)
    }

    /** True when every previous selection path still exists (non-ewm paths assumed durable). */
    private fun previousSelectionReadable(
        previousSelection: List<me.rosuh.easywatermark.data.model.ImageInfo>,
    ): Boolean {
        val fm = NSFileManager.defaultManager
        return previousSelection.all { info ->
            val path = info.uri.value
            if (path.isBlank()) return@all false
            if (!path.contains("ewm_src_")) return@all true
            fm.fileExistsAtPath(path)
        }
    }

    private fun decodeFilmstripThumb(path: String, frozenBucketPx: Int): ImageBitmap? {
        return runCatching {
            me.rosuh.easywatermark.render.IosImageIODecoder.decodeThumbnail(
                path,
                maxEdgePx = frozenBucketPx,
            )
        }.getOrNull()
    }

    /**
     * Decode missing filmstrip thumbs off-main into the repository cache.
     *
     * Deliberately does **not** bump [filmstripThumbEpoch]: a global epoch restart forces every
     * visible cell's produceState to drop and re-await, which janks scroll during mass import.
     * Visible cells seed from [IosPreviewImageRepository.peekCached] on composition; uncached
     * cells still load via produceState. [pickGeneration] gates every cache write after suspension (F16).
     * Bucket is frozen per request so a late decode cannot land under a different key/size.
     */
    private suspend fun prefetchFilmstripThumbs(paths: List<String>, pickGeneration: Long) {
        if (paths.isEmpty()) return
        if (!me.rosuh.easywatermark.session.IosPickGenerationGate.isPhotoCurrent(pickGeneration)) {
            return
        }
        // G4: bound concurrent filmstrip decodes to the same ceiling as stage concurrency.
        val gate = Semaphore(IOS_STAGING_MAX_CONCURRENCY)
        val targets = paths.filter { it.isNotBlank() }
        coroutineScope {
            targets.map { path ->
                async(Dispatchers.Default) {
                    gate.withPermit {
                        val frozenBucket = filmstripBucketPx()
                        val key = filmstripKey(path, frozenBucket)
                        previewImages.load(key) {
                            val decoded = runCatching {
                                decodeFilmstripThumb(path, frozenBucket)
                            }.getOrNull()
                            decoded?.takeIf {
                                me.rosuh.easywatermark.session.IosPickGenerationGate
                                    .isPhotoCurrent(pickGeneration)
                            }
                        }
                    }
                }
            }.awaitAll()
        }
        if (!me.rosuh.easywatermark.session.IosPickGenerationGate.isPhotoCurrent(pickGeneration)) {
            return
        }
        // Evidence-only timeline mark — no UI invalidation (no filmstripThumbEpoch bump).
        me.rosuh.easywatermark.session.ImportTimelineProbe.mark(
            "filmstrip_prefetch_done",
            pickGeneration,
            "n${targets.size}",
        )
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
            previewImages.clearPurposeFromOwner(IosPreviewPurpose.Watermarked)
            watermarkedPreviewSourcePath = null
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
 * - repository watermarked hit → 0 raster work
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
        val previewBucket = committedPreviewBucket
        val ox = draftOffset?.first ?: cur.offsetX
        val oy = draftOffset?.second ?: cur.offsetY
        hostBench.mark("sessionRead")

        // Cache hit only for committed (non-draft) paints at exact Session offset.
        if (!isDraft) {
            previewImages.cached(watermarkedPreviewKey(sourcePath, previewBucket))?.let { cached ->
                if (gen != previewGen) return
                previewBitmap = cached
                previewSourcePath = sourcePath
                watermarkedPreviewSourcePath = sourcePath
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

        val composed = if (isDraft) {
            withContext(Dispatchers.Default) {
                IosPreviewRaster.renderWatermarked(
                    sourcePath = sourcePath,
                    waterMark = wm,
                    offsetX = ox,
                    offsetY = oy,
                    maxEdgePx = PreviewResolutionPolicy.maxEdgeForPaint(
                        isDraft = true,
                        committedBucketPx = previewBucket,
                    ),
                )
            }
        } else {
            previewImages.load(watermarkedPreviewKey(sourcePath, previewBucket)) {
                withContext(Dispatchers.Default) {
                    IosPreviewRaster.renderWatermarked(
                        sourcePath = sourcePath,
                        waterMark = wm,
                        offsetX = ox,
                        offsetY = oy,
                        maxEdgePx = PreviewResolutionPolicy.maxEdgeForPaint(
                            isDraft = false,
                            committedBucketPx = previewBucket,
                        ),
                    )
                }
            } ?: return
        }
        hostBench.mark("raster")
        if (gen != previewGen) {
            hostBench.finish(mapOf("staleGen" to true, "hit" to false, "isDraft" to isDraft))
            return
        }

        // Never cache draft bitmaps as committed path entries (export must not see draft paint).
        if (!isDraft) {
            hostBench.mark("cachePut")
        }
        previewBitmap = composed
        previewSourcePath = sourcePath
        watermarkedPreviewSourcePath = sourcePath.takeIf { !isDraft }
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

    /**
     * After export sheet work: if the main canvas lost its watermarked bitmap (joint budget
     * eviction or gen race), re-raster the current selection so the editor is not blank.
     */
    private suspend fun ensureEditorPreviewAfterExport() {
        if (disposed) return
        val needsRebind = previewBitmap == null || watermarkedPreviewSourcePath == null
        if (!needsRebind) return
        previewGen += 1
        val gen = previewGen
        runCatching { renderPreviewForCurrentSelection(gen = gen) }
    }

    /** Fire-and-forget rebind from sheet dismiss (Main scope). */
    private fun rebindEditorPreviewIfBlank(scope: CoroutineScope) {
        if (disposed) return
        if (previewBitmap != null && watermarkedPreviewSourcePath != null) return
        scope.launch {
            ensureEditorPreviewAfterExport()
        }
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
 * ADR-0027: Content editor theme follow-photo (iOS). No wallpaper Material You.
 * Legacy force_dynamic_color key is ignored.
 */
private object IosContentThemePrefs {
    private const val KEY = "sp_follow_photo_ios"

    fun isFollowPhoto(): Boolean {
        val defaults = NSUserDefaults.standardUserDefaults
        if (defaults.objectForKey(KEY) == null) return true
        return defaults.boolForKey(KEY)
    }

    fun setFollowPhoto(enabled: Boolean) {
        NSUserDefaults.standardUserDefaults.setBool(enabled, forKey = KEY)
    }
}
