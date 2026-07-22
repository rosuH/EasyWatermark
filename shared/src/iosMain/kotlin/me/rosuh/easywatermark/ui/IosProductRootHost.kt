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
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import me.rosuh.easywatermark.ProductVersion
import me.rosuh.easywatermark.data.db.buildTemplateDatabase
import me.rosuh.easywatermark.data.model.FuncType
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
    private val onSaveToPhotos: (encodedBytes: ByteArray) -> Unit,
    private val onOpenUrl: (url: String) -> Unit = {},
    private val services: IosAppServices = defaultIosAppServices(),
) {
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
 * Cleared on config change. Instant filmstrip re-selection.
     */
    private val wmPreviewCache = mutableMapOf<String, ImageBitmap>()
    /** Source-only placeholders (no watermark) for instant switch feedback. */
    private val sourcePlaceholderCache = mutableMapOf<String, ImageBitmap>()
    /**
 * Filmstrip cell cache (path → small bitmap). Prefetched when a batch is staged so
 * Fling does not cold-decode / flash empty cells (snap-back perception).     */
    private val filmstripThumbCache = mutableMapOf<String, ImageBitmap>()
    /** Bumped after prefetch so produceState re-reads the map. */
    private var filmstripThumbEpoch by mutableStateOf(0)
    /** Export-sheet thumbs (path → small bitmap); avoids re-decode on fling recompose. */
    private val exportThumbCache = mutableMapOf<String, ImageBitmap>()
    private var previewGen: Int = 0
    private var isSaving by mutableStateOf(false)
    private var showEditor by mutableStateOf(false)
    private var productRoute by mutableStateOf(ProductShellNav.Route.Launch)
    /** Screen that opened About — used for correct back (not [showEditor]). */
    private var aboutReturnRoute by mutableStateOf(ProductShellNav.Route.Launch)
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

    private fun openAboutFrom(from: ProductShellNav.Route) {
        val (about, ret) = ProductShellNav.openAbout(from)
        aboutReturnRoute = ret
        productRoute = about
    }

    private fun closeAbout() {
        showOpenSource = false
        productRoute = ProductShellNav.aboutBack(aboutReturnRoute)
    }

    /** Swift edge: whether the product root is currently showing the editor. */
    fun isInEditor(): Boolean =
        productRoute == ProductShellNav.Route.Editor || showEditor

    fun viewController(): UIViewController = ComposeUIViewController {
        AppTheme(darkTheme = true) {
            val waterMark by services.waterMarkRepo.waterMark.collectAsState(WaterMark.default)
            val launchUi by services.session.launchScreenUiStateFlow.collectAsState()
            val exportJob by services.session.exportJobState.collectAsState()
            val sessionImages = launchUi.selectedImageList
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
                        onGoAbout = { openAboutFrom(ProductShellNav.Route.Launch) },
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
                            val watermarkedDisplayMatchesSelection =
                                dragPath.isNotEmpty() &&
                                    previewSourcePath == dragPath &&
                                    displayPreview != null &&
                                    wmPreviewCache[dragPath] === displayPreview
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
                                                onOffsetCommit = { x, y ->
                                                    if (dragPath.isEmpty()) {
                                                        return@clampPreviewOffsetDrag
                                                    }
                                                    // Triple identity: frozen drag path, displayed
                                                    // preview path, and live Session selection.
                                                    // Also refuse if display is no longer the
                                                    // watermarked cache bitmap for dragPath.
                                                    if (previewSourcePath != dragPath) {
                                                        return@clampPreviewOffsetDrag
                                                    }
                                                    if (wmPreviewCache[dragPath] !== displayPreview) {
                                                        return@clampPreviewOffsetDrag
                                                    }
                                                    val live = services.session
                                                        .launchScreenUiStateFlow
                                                        .value
                                                        .curImageInfo
                                                        ?.takeIf { it.uri.value == dragPath }
                                                        ?: return@clampPreviewOffsetDrag
                                                    // Sync Session sole commit → selected cache
                                                    // eviction → one gen bump → existing rerender.
                                                    services.session.applyOffset(
                                                        live.copy(offsetX = x, offsetY = y),
                                                    )
                                                    wmPreviewCache.remove(dragPath)
                                                    previewGen++
                                                    val gen = previewGen
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
                                }?.also { filmstripThumbCache[path] = it }
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
                            // Route alone drives [ProductShellHost] Editor→Launch transition.
                            // Keep showEditor in sync for isInEditor() / Swift edge queries.
                            productRoute = ProductShellNav.Route.Launch
                            showEditor = false
                        },
                        onAddMoreImages = onPickPhoto,
                        onShowSaveDialog = {
                            // C2: open shared Android Compose export panel (not immediate Photos write).
                            services.session.resetJobStatus()
                            sheetExportFinished = false
                            showSaveSheet = true
                        },
                        onGoAboutScreen = { openAboutFrom(ProductShellNav.Route.Editor) },
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
                        onConfigChange = { type, value ->
                            if (isBusy) return@EditorScreen
                            if (type == FuncType.Icon) {
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
                                        AppIntent.ApplyConfig(WatermarkConfigChange.from(type, value)),
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
                val completed = exportItems.count { it.jobState is JobState.Success }
                    .coerceAtLeast(exportJob.completedCount)
                val finished = sheetExportFinished || exportJob.isFinished
                val exporting = isSaving || exportJob.isSaving
                val primaryLabel = when {
                    finished -> stringResource(Res.string.share)
                    exporting -> stringResource(Res.string.dialog_save_exporting)
                    else -> stringResource(Res.string.dialog_export_to_gallery)
                }
                val listItems = exportItems.ifEmpty {
                    if (previewBitmap != null) listOf(ImageInfo(MediaRef("preview"))) else emptyList()
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
                    exportListSubtitle = "${if (finished) completed.coerceAtLeast(exportTotal) else completed}/$exportTotal",
                    imageCount = exportTotal,
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
                                    var saved = 0
                                    var lastPath: String? = null
                                    for (info in images) {
                                        val ref = (info.result?.data as? MediaRef)?.value
                                        if (info.jobState is JobState.Success && ref != null) {
                                            lastPath = ref
                                            val data = NSData.dataWithContentsOfFile(ref)
                                            val encodedBytes = data?.let { IosByteArrayInterop.fromNSData(it) }
                                            if (encodedBytes != null) {
                                                onSaveToPhotos(encodedBytes)
                                                saved++
                                            }
                                        }
                                    }
                                    outputPath = lastPath
                                    sheetExportFinished = true
                                    isSaving = false
                                    statusLine = if (saved > 0) {
                                        "Exported $saved/${images.size}"
                                    } else {
                                        "Nothing to export"
                                    }
                                } catch (t: Throwable) {
                                    statusLine = "Export failed: ${t.message}"
                                    isSaving = false
                                }
                            }
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
                        }?.also { exportThumbCache[path] = it }
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

    fun deliverPickedPhoto(bytes: ByteArray) {
        sourceBytes = bytes
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
    ) {
        deliverPickedPhotosBatch(listOf(bytes), append = append, renderPreview = renderPreview)
    }

    /**
 * Navigate to the editor shell **immediately** (before any photo bytes are ready).
 * Call from Swift as soon as the picker dismisses so the user is not blocked on
 * `loadTransferable` / decode / stage. No "Loading…" chrome.
     */
    fun showEditorShellImmediately() {
        showEditor = true
        productRoute = ProductShellNav.Route.Editor
        // Leave preview blank (silent) until stage + placeholder / raster fill it.
        statusLine = ""
    }

    /**
 * Stage all [images] in **one** EnterEditor (filmstrip fills at once), prefetch filmstrip
 * Thumbs so fling is cold-miss free, then optionally raster the focused preview. *
 * Prefer [showEditorShellImmediately] first so UI is not gated on photo IO.
 * Swift should load **all** picker payloads then call this once (not per-item append).
     */
    @Throws(Exception::class)
    suspend fun deliverPickedPhotosBatch(
        images: List<ByteArray>,
        append: Boolean = false,
        renderPreview: Boolean = true,
    ) {
        require(images.isNotEmpty()) { "deliverPickedPhotosBatch: empty" }
        sourceBytes = images.first()
        // Ensure shell is visible even if caller skipped [showEditorShellImmediately].
        showEditor = true
        productRoute = ProductShellNav.Route.Editor
        if (!append) {
            // Fresh pick: drop old strip/preview so we never show stale cells while staging.
            wmPreviewCache.clear()
            sourcePlaceholderCache.clear()
            filmstripThumbCache.clear()
            filmstripThumbEpoch += 1
            previewBitmap = null
            previewSourcePath = null
        }
        withContext(Dispatchers.Default) {
            services.stagePickedImagesBytes(images, append = append)
        }
        statusLine = ""

        val launch = services.session.launchScreenUiStateFlow.first()
        val paths = launch.selectedImageList.map { it.uri.value }.filter { it.isNotBlank() }
        val focusPath = (launch.curImageInfo ?: launch.selectedImageList.firstOrNull())?.uri?.value

        // Instant source placeholder for the focused image (no watermark yet).
        if (renderPreview && focusPath != null) {
            val placeholder = sourcePlaceholderCache[focusPath]
                ?: withContext(Dispatchers.Default) {
                    IosPreviewRaster.decodeSourcePlaceholder(focusPath)
                }?.also { sourcePlaceholderCache[focusPath] = it }
            if (placeholder != null) {
                previewBitmap = placeholder
                previewSourcePath = focusPath
            }
        }

        // Prefetch ALL filmstrip thumbs before/alongside full raster so fling never snaps
        // on empty→filled cell churn.
        hostScope.launch {
            try {
                prefetchFilmstripThumbs(paths)
            } catch (_: Throwable) {
                // Best-effort; produceState cold path still works.
            }
        }

        if (!renderPreview) {
            return
        }
        // Full watermarked preview is async; deliver returns after stage + placeholder.
        previewGen += 1
        val gen = previewGen
        hostScope.launch {
            try {
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

    /** Decode missing filmstrip thumbs off-main; bumps [filmstripThumbEpoch] once when done. */
    private suspend fun prefetchFilmstripThumbs(paths: List<String>) {
        if (paths.isEmpty()) return
        val missing = paths.filter { it.isNotBlank() && !filmstripThumbCache.containsKey(it) }
        if (missing.isEmpty()) return
        coroutineScope {
            missing.map { path ->
                async(Dispatchers.Default) {
                    runCatching {
                        decodeFilmstripThumb(path)?.let { filmstripThumbCache[path] = it }
                    }
                }
            }.awaitAll()
        }
        withContext(Dispatchers.Main) {
            filmstripThumbEpoch += 1
        }
    }

    @Throws(Exception::class)
    suspend fun deliverIconBytesAndAwait(bytes: ByteArray) {
        isBusy = true
        try {
            val previousRef = services.waterMarkRepo.waterMark.first().iconUri
            val path = IosIconPersistence.writeIconBytes(bytes)
            services.session.dispatchAndAwait(
                AppIntent.ApplyConfig(WatermarkConfigChange.Icon(MediaRef(path))),
            )
            IosIconPersistence.deleteIfOwned(previousRef.value)
            iconBytes = bytes
            wmPreviewCache.clear()
            previewGen += 1
            renderPreviewForCurrentSelection(gen = previewGen)
        } finally {
            isBusy = false
        }
    }

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
    private suspend fun renderPreviewForCurrentSelection(gen: Int) {
        val launch = services.session.launchScreenUiStateFlow.first()
        val cur = launch.curImageInfo ?: launch.selectedImageList.firstOrNull() ?: return
        val sourcePath = cur.uri.value
        if (sourcePath.isBlank()) return
        val wm = services.waterMarkRepo.waterMark.first()

        wmPreviewCache[sourcePath]?.let { cached ->
            if (gen != previewGen) return
            previewBitmap = cached
            previewSourcePath = sourcePath
            return
        }

        val composed = withContext(Dispatchers.Default) {
            IosPreviewRaster.renderWatermarked(
                sourcePath = sourcePath,
                waterMark = wm,
                offsetX = cur.offsetX,
                offsetY = cur.offsetY,
            )
        }
        if (gen != previewGen) return

        wmPreviewCache[sourcePath] = composed
        while (wmPreviewCache.size > 8) {
            val oldest = wmPreviewCache.keys.firstOrNull() ?: break
            wmPreviewCache.remove(oldest)
        }
        while (sourcePlaceholderCache.size > 12) {
            val oldest = sourcePlaceholderCache.keys.firstOrNull() ?: break
            sourcePlaceholderCache.remove(oldest)
        }
        while (filmstripThumbCache.size > 48) {
            val oldest = filmstripThumbCache.keys.firstOrNull() ?: break
            filmstripThumbCache.remove(oldest)
        }
        previewBitmap = composed
        previewSourcePath = sourcePath
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
