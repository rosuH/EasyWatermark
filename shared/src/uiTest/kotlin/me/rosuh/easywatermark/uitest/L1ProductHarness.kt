package me.rosuh.easywatermark.uitest

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.ImageBitmapConfig
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.createFontFamilyResolver
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import me.rosuh.easywatermark.ProductVersion
import me.rosuh.easywatermark.data.model.ExportedMedia
import me.rosuh.easywatermark.data.model.ImageInfo
import me.rosuh.easywatermark.data.model.ImageInfoUi
import me.rosuh.easywatermark.data.model.MediaRef
import me.rosuh.easywatermark.data.model.UserPreferences
import me.rosuh.easywatermark.data.model.WaterMark
import me.rosuh.easywatermark.data.model.WatermarkConfigChange
import me.rosuh.easywatermark.data.model.WatermarkMode
import me.rosuh.easywatermark.data.model.WatermarkTileMode
import me.rosuh.easywatermark.data.model.entity.Template
import me.rosuh.easywatermark.data.repo.WaterMarkRepository
import me.rosuh.easywatermark.domain.WatermarkConfigEditor
import me.rosuh.easywatermark.render.CommonWatermarkPipeline
import me.rosuh.easywatermark.render.TextRasterEnv
import me.rosuh.easywatermark.session.ExportFailure
import me.rosuh.easywatermark.session.ExportOutcome
import me.rosuh.easywatermark.session.ExportPipelinePort
import me.rosuh.easywatermark.session.MediaLibraryPort
import me.rosuh.easywatermark.shared.generated.resources.Res
import me.rosuh.easywatermark.shared.generated.resources.action_pick
import me.rosuh.easywatermark.shared.generated.resources.cd_add_more_images
import me.rosuh.easywatermark.shared.generated.resources.cd_back
import me.rosuh.easywatermark.shared.generated.resources.tips_ios_library_read_upsell_continue
import me.rosuh.easywatermark.shared.generated.resources.tips_ios_library_read_upsell_denied
import me.rosuh.easywatermark.shared.generated.resources.tips_ios_library_read_upsell_settings
import me.rosuh.easywatermark.shared.generated.resources.tips_ios_library_read_upsell_title
import me.rosuh.easywatermark.ui.AboutPageLogo
import me.rosuh.easywatermark.ui.EditorLayoutClass
import me.rosuh.easywatermark.ui.EditorOptionItem
import me.rosuh.easywatermark.ui.EditorScreen
import me.rosuh.easywatermark.ui.EditorUiIcons
import me.rosuh.easywatermark.ui.GalleryDialogShell
import me.rosuh.easywatermark.ui.LaunchScreen
import me.rosuh.easywatermark.ui.ProductShellHost
import me.rosuh.easywatermark.ui.ProductShellNav
import me.rosuh.easywatermark.ui.RecoveryScreen
import me.rosuh.easywatermark.ui.SharedProductDrawables
import me.rosuh.easywatermark.ui.editorLayoutClass
import me.rosuh.easywatermark.ui.about.AboutDevCard
import me.rosuh.easywatermark.ui.about.AboutScreen
import me.rosuh.easywatermark.ui.about.AboutScreenIcons
import me.rosuh.easywatermark.ui.about.OpenSourceOverlayHost
import me.rosuh.easywatermark.ui.clampPreviewOffsetDrag
import me.rosuh.easywatermark.ui.compose.EwmConfirmDialog
import me.rosuh.easywatermark.ui.compose.IconWatermarkOption
import me.rosuh.easywatermark.ui.compose.TextColorOption
import me.rosuh.easywatermark.ui.iconPainter
import me.rosuh.easywatermark.ui.label
import me.rosuh.easywatermark.ui.save.ExportRecoveryUi
import me.rosuh.easywatermark.ui.save.ExportRecoveryUiState
import me.rosuh.easywatermark.ui.save.SaveExportSheetShell
import me.rosuh.easywatermark.ui.theme.AppTheme
import me.rosuh.easywatermark.ui.theme.MotionPolicy
import me.rosuh.easywatermark.ui.theme.ProvideMotionPolicy
import me.rosuh.easywatermark.ui.Image as GalleryImage
import org.jetbrains.compose.resources.stringResource

/**
 * In-memory L1 product tree: [ProductShellHost] + [LaunchScreen] / [EditorScreen] /
 * [AboutScreen], with [SaveExportSheetShell] overlaid like the Desktop host.
 *
 * Watermark config is the real [WaterMarkRepository] + [WatermarkConfigEditor] over
 * [InMemoryPreferencesDataStore]. UI and test assertions read [repo.waterMark].
 * Templates stay an in-memory list. Default composition is compact (390×844)
 * so stacked chrome tags stay addressable; callers pass a larger [Modifier]
 * under a density-1 Skiko host (`runSkikoComposeUiTest`) to exercise
 * [editorLayoutClass]. Preview is a real [CommonWatermarkPipeline]
 * compose over a solid fixture (no platform decode).
 */
internal class L1Session {
    var route by mutableStateOf(ProductShellNav.Route.Launch)
    var aboutReturn by mutableStateOf(ProductShellNav.Route.Launch)
    val repo = WaterMarkRepository(
        dataStore = InMemoryPreferencesDataStore(),
        defaultTextProvider = { WaterMark.default.text },
        tileModeFromStorageId = { WatermarkTileMode.fromStorageId(it) },
        logError = {},
    )
    val editor = WatermarkConfigEditor(repo)
    /**
     * v2 [StandardTestDispatcher] does not run [rememberCoroutineScope] jobs
     * during [androidx.compose.ui.test.ComposeUiTest.waitUntil] / [androidx.compose.ui.test.ComposeUiTest.awaitIdle].
     * Unconfined starts the collect/apply until the first suspend; our in-memory
     * store does not hop, so the repo flow updates before the click returns.
     */
    private val repoScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)

    /**
     * Snapshot of [repo.waterMark] (collected on [repoScope]). Tests and the
     * editor read this — not a locally applied copy.
     */
    var waterMark by mutableStateOf(WaterMark.default)
        private set
    var repoWaterMarkBound: Boolean = false
        private set

    init {
        repoScope.launch {
            repo.waterMark.collect { next ->
                waterMark = next
                repoWaterMarkBound = true
            }
        }
    }

    var images by mutableStateOf(emptyList<ImageInfoUi>())
    var selected by mutableStateOf<ImageInfoUi?>(null)
    var templates by mutableStateOf(emptyList<Template>())
    var showSave by mutableStateOf(false)
    var showGallery by mutableStateOf(false)
    var showRecovery by mutableStateOf(false)
    var showLibraryReadUpsell by mutableStateOf(false)
    var preferInAppGallery by mutableStateOf(false)
    var followPhotoOn by mutableStateOf(true)
    var followWallpaperOn by mutableStateOf(false)
    var showBounds by mutableStateOf(false)
    var showOpenSource by mutableStateOf(false)
    var holdExport by mutableStateOf(false)
    var exportCancelled by mutableStateOf(false)
    var libraryReadDenied by mutableStateOf(false)
    var openGalleryClicks by mutableStateOf(0)
    var recoveryClosed by mutableStateOf(false)
    var exportRecovery by mutableStateOf(
        ExportRecoveryUi.fromJob(
            isSaving = false,
            isFinished = false,
            successCount = 0,
            failureCount = 0,
            processedCount = 0,
            totalCount = 0,
        ),
    )
    var outputPrefs by mutableStateOf(UserPreferences.DEFAULT)
    val exportPort = RecordingExportPort()
    val mediaLibrary = EmptyMediaLibraryPort()
    val fixtureImage = ImageInfoUi(
        uri = MediaRef("mem://l1-fixture"),
        width = 64,
        height = 48,
    )
    val extraImage = ImageInfoUi(
        uri = MediaRef("mem://l1-more"),
        width = 64,
        height = 48,
    )
    val galleryImages = listOf(
        GalleryImage(id = 1, uri = MediaRef("mem://gal-1"), name = "a.jpg", size = 1, date = 0),
        GalleryImage(id = 2, uri = MediaRef("mem://gal-2"), name = "b.jpg", size = 1, date = 0),
    )
    private var nextTemplateId = 1

    fun seamFeedImage() {
        images = listOf(fixtureImage)
        selected = fixtureImage
        route = ProductShellNav.Route.Editor
    }

    /** Share-in seam: preload a selection. Not ACTION_SEND. */
    fun shareInSelection(items: List<ImageInfoUi> = listOf(fixtureImage)) {
        images = items
        selected = items.firstOrNull()
        route = ProductShellNav.Route.Editor
    }

    fun onLaunchPick() {
        when {
            libraryReadDenied -> showLibraryReadUpsell = true
            preferInAppGallery -> showGallery = true
            else -> seamFeedImage()
        }
    }

    fun addMoreImage() {
        if (images.none { it.uri == extraImage.uri }) {
            images = images + extraImage
        }
    }

    fun updateSelectedOffset(offsetX: Float, offsetY: Float) {
        val current = selected ?: return
        val next = current.copy(offsetX = offsetX, offsetY = offsetY)
        images = images.map { if (it.uri == current.uri) next else it }
        selected = next
    }

    fun beginHeldExport() {
        exportCancelled = false
        exportRecovery = ExportRecoveryUi.fromJob(
            isSaving = true,
            isFinished = false,
            successCount = 0,
            failureCount = 0,
            processedCount = 0,
            totalCount = 1,
        )
    }

    fun cancelHeldExport() {
        exportCancelled = true
        exportRecovery = ExportRecoveryUi.fromJob(
            isSaving = false,
            isFinished = true,
            successCount = 0,
            failureCount = 0,
            processedCount = 0,
            totalCount = 1,
        )
    }

    fun commitGallerySelection(picked: List<GalleryImage>) {
        if (picked.isEmpty()) return
        val next = picked.map { ImageInfoUi(uri = it.uri, width = 64, height = 48) }
        images = next
        selected = next.first()
        route = ProductShellNav.Route.Editor
    }

    fun applyChange(change: WatermarkConfigChange) {
        repoScope.launch {
            when (change) {
                is WatermarkConfigChange.Text -> editor.updateText(change.text)
                is WatermarkConfigChange.Icon -> editor.updateIcon(change.icon)
                is WatermarkConfigChange.MarkMode -> editor.updateMarkMode(change.mode)
                is WatermarkConfigChange.Color -> editor.updateTextColor(change.color)
                is WatermarkConfigChange.AlphaPercent -> editor.updateAlpha(change.percent)
                is WatermarkConfigChange.Degree -> editor.updateDegree(change.degree)
                is WatermarkConfigChange.TextSize -> editor.updateTextSize(change.size)
                is WatermarkConfigChange.Typeface -> editor.updateTextTypeface(change.typeface)
                is WatermarkConfigChange.TextStyle -> editor.updateTextStyle(change.style)
                is WatermarkConfigChange.TileMode -> editor.updateTileMode(change.tileMode)
                is WatermarkConfigChange.HorizontalGap -> editor.updateHorizon(change.gap)
                is WatermarkConfigChange.VerticalGap -> editor.updateVertical(change.gap)
            }
        }
    }

    fun addTemplate(content: String) {
        templates = templates + Template(
            id = nextTemplateId++,
            content = content,
            creationDate = null,
            lastModifiedDate = null,
        )
    }

    fun updateTemplate(template: Template) {
        templates = templates.map { if (it.id == template.id) template else it }
    }

    fun deleteTemplate(template: Template) {
        templates = templates.filter { it.id != template.id }
    }

    fun useTemplate(template: Template) {
        applyChange(WatermarkConfigChange.Text(template.content.orEmpty()))
    }

    fun exportSelected() {
        val item = (selected ?: images.firstOrNull())?.toImageInfo() ?: return
        val outcome = runBlocking {
            exportPort.exportOne(item, waterMark, outputPrefs)
        }
        exportRecovery = if (outcome.isSuccess()) {
            ExportRecoveryUi.fromJob(
                isSaving = false,
                isFinished = true,
                successCount = 1,
                failureCount = 0,
                processedCount = 1,
                totalCount = 1,
            )
        } else {
            ExportRecoveryUi.fromJob(
                isSaving = false,
                isFinished = true,
                successCount = 0,
                failureCount = 1,
                processedCount = 1,
                totalCount = 1,
            )
        }
    }
}

internal class RecordingExportPort : ExportPipelinePort {
    var calls: Int = 0
        private set
    var failNext: Boolean = false

    override suspend fun exportOne(
        imageInfo: ImageInfo,
        config: WaterMark,
        prefs: UserPreferences,
    ): ExportOutcome {
        calls += 1
        if (failNext) {
            failNext = false
            return ExportOutcome.failure(ExportFailure.Io(message = "L1 injected failure"))
        }
        return ExportOutcome.success(
            ExportedMedia(
                ref = MediaRef("mem://l1-exported"),
                width = imageInfo.width,
                height = imageInfo.height,
                format = prefs.outputFormat,
                byteCount = 128L,
            ),
        )
    }
}

/** Phone fixture from [me.rosuh.easywatermark.ui.EditorLayoutClassTest] — fits the Skiko 768-tall host. */
internal val L1CompactWidthDp = 360f
internal val L1CompactHeightDp = 640f

/** Expanded floor (ADR-0026). 800×600 fits the 1024×768 px host at harness density 1. */
internal val L1ExpandedWidthDp = 800f
internal val L1ExpandedHeightDp = 600f

internal fun composeL1Preview(mark: WaterMark): ImageBitmap {
    val bg = ImageBitmap(320, 240, ImageBitmapConfig.Argb8888)
    CanvasDrawScope().draw(
        density = Density(1f),
        layoutDirection = LayoutDirection.Ltr,
        canvas = Canvas(bg),
        size = Size(320f, 240f),
    ) { drawRect(Color(0xFF2A4A6B)) }
    val icon = if (mark.markMode == WatermarkMode.Image) {
        val cell = ImageBitmap(32, 32, ImageBitmapConfig.Argb8888)
        CanvasDrawScope().draw(
            density = Density(1f),
            layoutDirection = LayoutDirection.Ltr,
            canvas = Canvas(cell),
            size = Size(32f, 32f),
        ) { drawRect(Color(0xFFFFFFFF)) }
        cell
    } else {
        null
    }
    return CommonWatermarkPipeline.compose(
        background = bg,
        config = mark,
        env = TextRasterEnv(
            fontFamilyResolver = createFontFamilyResolver(),
            density = Density(1f),
        ),
        icon = icon,
    )
}

/** Same unused-library shape as Session tests; L1 gallery uses [L1Session.galleryImages]. */
internal class EmptyMediaLibraryPort : MediaLibraryPort {
    override suspend fun listImages(): List<me.rosuh.easywatermark.ui.Image> = emptyList()
    override suspend fun enrichPickerRefs(
        refs: List<MediaRef>,
    ): List<me.rosuh.easywatermark.ui.Image> = emptyList()
}

@Composable
internal fun L1ProductTree(
    session: L1Session,
    modifier: Modifier = Modifier.size(L1CompactWidthDp.dp, L1CompactHeightDp.dp),
) {
    val previewBitmap = remember(session.waterMark) { composeL1Preview(session.waterMark) }
    ProvideMotionPolicy(MotionPolicy.Off) {
        AppTheme(darkTheme = true) {
            BoxWithConstraints(modifier) {
                val layoutClass = editorLayoutClass(maxWidth.value, maxHeight.value)
                Box(Modifier.fillMaxSize()) {
                    ProductShellHost(
                        route = session.route,
                        aboutReturn = session.aboutReturn,
                    ) { page ->
                        when (page) {
                            ProductShellNav.Route.Launch -> LaunchScreen(
                                aboutIcon = SharedProductDrawables.aboutPainter(),
                                onPickImage = { session.onLaunchPick() },
                                onGoAbout = {
                                    session.aboutReturn = ProductShellNav.Route.Launch
                                    session.route = ProductShellNav.Route.About
                                },
                                startLogoAnimation = false,
                            )
                            ProductShellNav.Route.Editor -> L1Editor(
                                session = session,
                                previewBitmap = previewBitmap,
                                layoutClass = layoutClass,
                            )
                            ProductShellNav.Route.About -> L1About(session)
                        }
                    }
                    if (session.showSave) {
                        L1SaveSheet(session, previewBitmap)
                    }
                    if (session.showGallery) {
                        L1Gallery(session, previewBitmap)
                    }
                    if (session.showRecovery) {
                        RecoveryScreen(
                            crashInfo = "L1 recovery fixture",
                            onCopy = {},
                            onSendEmail = {},
                            onTelegram = {},
                            onStore = {},
                            onCloseRecovery = {
                                session.showRecovery = false
                                session.recoveryClosed = true
                            },
                        )
                    }
                    OpenSourceOverlayHost(
                        visible = session.showOpenSource,
                        onBack = { session.showOpenSource = false },
                        onOpenLink = {},
                        backIcon = SharedProductDrawables.backPainter(),
                    )
                    if (session.showLibraryReadUpsell) {
                        EwmConfirmDialog(
                            onDismissRequest = {
                                session.showLibraryReadUpsell = false
                                session.seamFeedImage()
                            },
                            title = stringResource(Res.string.tips_ios_library_read_upsell_title),
                            text = stringResource(Res.string.tips_ios_library_read_upsell_denied),
                            confirmLabel = stringResource(Res.string.tips_ios_library_read_upsell_settings),
                            dismissLabel = stringResource(Res.string.tips_ios_library_read_upsell_continue),
                            onConfirm = { session.showLibraryReadUpsell = false },
                            confirmTestTag = "iosLibraryReadUpsellConfirm",
                            dismissTestTag = "iosLibraryReadUpsellContinue",
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun L1Editor(
    session: L1Session,
    previewBitmap: ImageBitmap,
    layoutClass: EditorLayoutClass,
) {
    EditorScreen(
        imageList = session.images,
        waterMark = session.waterMark,
        selectedImage = session.selected,
        templates = session.templates,
        icons = EditorUiIcons(
            back = SharedProductDrawables.backPainter(),
            addMoreImages = SharedProductDrawables.pickerImagePainter(),
            save = SharedProductDrawables.savePainter(),
            about = SharedProductDrawables.aboutPainter(),
            templateList = SharedProductDrawables.templateListPainter(),
            templateEdit = SharedProductDrawables.templateEditPainter(),
            templateDelete = SharedProductDrawables.templateDeletePainter(),
        ),
        preview = { previewModifier ->
            val selected = session.selected
            Image(
                bitmap = previewBitmap,
                contentDescription = "Watermark preview",
                contentScale = ContentScale.Fit,
                modifier = previewModifier
                    .fillMaxSize()
                    .clampPreviewOffsetDrag(
                        enabled = true,
                        selectionId = selected?.uri?.value.orEmpty(),
                        isClamp = session.waterMark.tileMode == WatermarkTileMode.CLAMP,
                        imageWidth = previewBitmap.width.toFloat(),
                        imageHeight = previewBitmap.height.toFloat(),
                        offsetX = selected?.offsetX ?: 0.5f,
                        offsetY = selected?.offsetY ?: 0.5f,
                        onOffsetCommit = { x, y -> session.updateSelectedOffset(x, y) },
                    ),
            )
        },
        thumbnail = { _, contentDescription, thumbModifier ->
            Image(
                bitmap = previewBitmap,
                contentDescription = contentDescription,
                contentScale = ContentScale.Crop,
                modifier = thumbModifier.background(MaterialTheme.colorScheme.surfaceVariant),
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
                customText = "",
                modifier = optionModifier,
                onColorSelected = onColor,
            )
        },
        iconOption = { optionModifier, mark, onIcon ->
            IconWatermarkOption(
                hasIcon = !mark.iconUri.isEmpty(),
                pickLabel = "Pick icon",
                modifier = optionModifier,
                onPick = { onIcon(MediaRef("mem://l1-icon")) },
            )
        },
        onBack = { session.route = ProductShellNav.Route.Launch },
        onAddMoreImages = { session.addMoreImage() },
        onShowSaveDialog = { session.showSave = true },
        onGoAboutScreen = {
            session.aboutReturn = ProductShellNav.Route.Editor
            session.route = ProductShellNav.Route.About
        },
        onImageSelected = { session.selected = it },
        onConfigChange = { session.applyChange(it) },
        onUseTemplate = { session.useTemplate(it) },
        onAddTemplate = { session.addTemplate(it) },
        onUpdateTemplate = { session.updateTemplate(it) },
        onDeleteTemplate = { session.deleteTemplate(it) },
        layoutClass = layoutClass,
    )
}

@Composable
private fun L1About(session: L1Session) {
    AboutScreen(
        versionName = ProductVersion.NAME,
        showBounds = session.showBounds,
        icons = AboutScreenIcons(
            back = SharedProductDrawables.backPainter(),
            version = SharedProductDrawables.versionPainter(),
            rating = SharedProductDrawables.ratePainter(),
            feedback = SharedProductDrawables.feedbackPainter(),
            updateLog = SharedProductDrawables.updateLogPainter(),
            openSource = SharedProductDrawables.openSourcePainter(),
            privacyZh = SharedProductDrawables.privacyZhPainter(),
            privacyEn = SharedProductDrawables.privacyEnPainter(),
        ),
        developerCard = AboutDevCard(
            title = "Developed with ♥ by rosu",
            description = "L1",
            avatar = SharedProductDrawables.avatarDevPainter(),
        ),
        designerCard = AboutDevCard(
            title = "Designed with ♥ by tovi",
            description = "L1",
            avatar = SharedProductDrawables.avatarToviPainter(),
        ),
        onBack = { session.route = session.aboutReturn },
        onVersion = {},
        onRate = {},
        onFeedback = {},
        onUpdateLog = {},
        onOpenSource = { session.showOpenSource = true },
        onPrivacyZh = {},
        onPrivacyEn = {},
        onDeveloper = {},
        onDesigner = {},
        onToggleBounds = { session.showBounds = it },
        showFollowWallpaperSwitch = true,
        followWallpaperOn = session.followWallpaperOn,
        onToggleFollowWallpaper = { session.followWallpaperOn = it },
        followPhotoOn = session.followPhotoOn,
        onToggleFollowPhoto = { session.followPhotoOn = it },
        showPreferInAppGallerySwitch = true,
        preferInAppGallery = session.preferInAppGallery,
        onTogglePreferInAppGallery = { session.preferInAppGallery = it },
        logo = { modifier -> AboutPageLogo(modifier = modifier, animate = false) },
    )
}

@Composable
private fun L1Gallery(session: L1Session, previewBitmap: ImageBitmap) {
    GalleryDialogShell(
        images = session.galleryImages,
        title = stringResource(Res.string.action_pick),
        closeIcon = SharedProductDrawables.closePainter(),
        searchIcon = SharedProductDrawables.searchPainter(),
        checkIcon = SharedProductDrawables.galleryCheckPainter(),
        selectedCountIcon = SharedProductDrawables.saveDonePainter(),
        closeContentDescription = stringResource(Res.string.cd_back),
        searchContentDescription = stringResource(Res.string.cd_add_more_images),
        selectedCountContentDescription = stringResource(Res.string.cd_add_more_images),
        onLoadImages = {},
        onDismiss = { picked ->
            session.showGallery = false
            session.commitGallerySelection(picked)
        },
        thumbnail = { _, contentDescription, thumbModifier ->
            Image(
                bitmap = previewBitmap,
                contentDescription = contentDescription,
                contentScale = ContentScale.Crop,
                modifier = thumbModifier,
            )
        },
    )
}

@Composable
private fun L1SaveSheet(session: L1Session, previewBitmap: ImageBitmap) {
    val recovery: ExportRecoveryUiState = session.exportRecovery
    SaveExportSheetShell(
        items = session.images,
        selectedFormat = session.outputPrefs.outputFormat,
        quality = session.outputPrefs.compressLevel,
        primaryActionLabel = "Export",
        exportListSubtitle = ExportRecoveryUi.summaryLine(recovery),
        imageCount = session.images.size,
        isExporting = recovery.isExporting,
        showCancelButton = recovery.showCancel,
        showRetryFailedButton = recovery.showRetryFailed,
        statusContentDescription = ExportRecoveryUi.contentDescription(recovery),
        countsLine = ExportRecoveryUi.distinctCountsLine(recovery),
        exportTotalCount = recovery.totalCount,
        exportSuccessCount = recovery.successCount,
        exportFailureCount = recovery.failureCount,
        useLargeDialog = false,
        onDismiss = { session.showSave = false },
        onFormatClick = { format ->
            session.outputPrefs = session.outputPrefs.copy(outputFormat = format)
        },
        onQualityChange = { quality ->
            session.outputPrefs = session.outputPrefs.copy(compressLevel = quality)
        },
        onExportClick = {
            if (session.holdExport) session.beginHeldExport() else session.exportSelected()
        },
        onCancelClick = { session.cancelHeldExport() },
        onRetryFailedClick = { session.exportSelected() },
        onOpenGalleryClick = { session.openGalleryClicks += 1 },
        itemKey = { it.uri.value },
        thumbnail = { _, thumbModifier ->
            Image(
                bitmap = previewBitmap,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = thumbModifier,
            )
        },
    )
}
