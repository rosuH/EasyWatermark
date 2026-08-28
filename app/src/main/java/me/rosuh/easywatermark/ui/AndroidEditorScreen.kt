package me.rosuh.easywatermark.ui

import android.graphics.Bitmap
import android.graphics.Shader
import android.net.Uri
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.skydoves.compose.stability.runtime.TraceRecomposition
import me.rosuh.easywatermark.data.model.FuncTitleModel
import me.rosuh.easywatermark.data.model.FuncType
import me.rosuh.easywatermark.data.model.ImageInfo
import me.rosuh.easywatermark.data.model.ImageInfoUi
import me.rosuh.easywatermark.data.model.WaterMark
import me.rosuh.easywatermark.data.model.WatermarkConfigChange
import me.rosuh.easywatermark.data.model.WatermarkMode
import me.rosuh.easywatermark.data.model.entity.Template
import me.rosuh.easywatermark.render.AndroidCommonRaster
import me.rosuh.easywatermark.render.AndroidPreviewWorkingSet
import me.rosuh.easywatermark.render.PreviewImageRepository
import me.rosuh.easywatermark.render.OverlayPreviewChrome
import me.rosuh.easywatermark.render.OverlayPreviewPolicy
import me.rosuh.easywatermark.render.PreviewKey
import me.rosuh.easywatermark.render.PreviewPurpose
import me.rosuh.easywatermark.render.PreviewResolutionPolicy
import me.rosuh.easywatermark.render.PreviewSourceReuseProbe
import me.rosuh.easywatermark.render.PreviewWorkingSetBudget
import me.rosuh.easywatermark.render.WatermarkIconCache
import me.rosuh.easywatermark.render.neighborIndices
import me.rosuh.easywatermark.ui.DraftRenderConflator
import me.rosuh.easywatermark.ui.LiveOverlayPreview
import me.rosuh.easywatermark.ui.OverlayCell
import me.rosuh.easywatermark.ui.overlayCellDisplaySize
import me.rosuh.easywatermark.ui.overlayCellFrom
import me.rosuh.easywatermark.ui.withOffset
import me.rosuh.easywatermark.ui.compose.ColorOption
import me.rosuh.easywatermark.ui.compose.IconOption
import me.rosuh.easywatermark.ui.image.ProductAsyncImage
import me.rosuh.easywatermark.ui.image.ProductThumb
import me.rosuh.easywatermark.ui.image.rememberProductThumbBitmap
import me.rosuh.easywatermark.ui.theme.ContentEditorThemeHost
import me.rosuh.easywatermark.ui.theme.EwmTheme
import me.rosuh.easywatermark.ui.theme.currentMotionPolicy
import me.rosuh.easywatermark.ui.theme.motionDurationMs
import me.rosuh.easywatermark.utils.bitmap.decodePreviewSourceBypassingCache
import me.rosuh.easywatermark.utils.bitmap.decodeSampledBitmapFromResourceSync
import me.rosuh.easywatermark.utils.ktx.obtainTileMode
import me.rosuh.easywatermark.utils.ktx.toUri
import kotlin.math.min

/**
 * Android host for shared [me.rosuh.easywatermark.ui.EditorScreen].
 * Named AndroidEditorScreen (file AndroidEditorScreen.kt) to avoid JVM clash with shared EditorScreenKt.
 * Supplies resources, Coil thumbnails, Color/Icon edges, and [WaterMarkCanvas] preview.
 *
 * Accepts immutable [ImageInfoUi] for filmstrip/preview (P0). Session still applies offsets via
 * [ImageInfo] at the host boundary.
 */
@Composable
fun AndroidEditorScreen(
    imageList: List<ImageInfoUi>,
    waterMark: WaterMark,
    onBack: () -> Unit,
    onOffsetChanged: (ImageInfo) -> Unit,
    modifier: Modifier = Modifier,
    selectedImage: ImageInfoUi? = null,
    onImageSelected: (ImageInfoUi) -> Unit = {},
    onWaterMrkChange: (WatermarkConfigChange) -> Unit = {},
    onIconPicked: (Uri) -> Unit = {},
    onAddMoreImages: () -> Unit = { },
    onShowSaveDialog: () -> Unit = { },
    onGoAboutScreen: () -> Unit = { },
    templates: List<Template> = emptyList(),
    onUseTemplate: (Template) -> Unit = {},
    onAddTemplate: (String) -> Unit = {},
    onUpdateTemplate: (Template) -> Unit = {},
    onDeleteTemplate: (Template) -> Unit = {},
    onTemplateSheetVisibilityChange: (Boolean) -> Unit = {},
    /** ADR-0027: full Editor ColorScheme from selected photo (default on). */
    followPhoto: Boolean = true,
    onUpdateUriFailed: (SecurityException) -> Unit = { },
) {
    val colorModel = remember { FuncTitleModel(FuncType.Color) }
    val editorScope = rememberCoroutineScope()
    val previewImages = remember {
        PreviewImageRepository<Bitmap>(
            ownerScope = editorScope,
            approxBytes = { bmp: Bitmap -> bmp.allocationByteCount.toLong() },
        )
    }
    val iconCache = remember { WatermarkIconCache<Bitmap>() }
    val persistHandler = remember {
        mutableStateOf<suspend (WatermarkConfigChange) -> Unit>({ _ -> })
    }
    val persistConflator = remember {
        DraftRenderConflator<WatermarkConfigChange>(editorScope) { persistHandler.value(it) }
    }
    persistHandler.value = { change -> onWaterMrkChange(change) }
    DisposableEffect(previewImages) {
        AndroidPreviewWorkingSet.attach(previewImages)
        onDispose {
            persistConflator.close()
            AndroidPreviewWorkingSet.detach(previewImages)
            previewImages.closeFromOwner()
            iconCache.invalidate()
        }
    }
    val seedSel = selectedImage ?: imageList.firstOrNull()
    val seedUri = seedSel?.uri?.value
    // ADR-0028: theme seed via product Coil path (shared max-edge with filmstrip).
    val seedBitmap = rememberProductThumbBitmap(
        ref = seedSel?.uri,
        maxEdgePx = ProductThumb.UI_THUMB_MAX_EDGE,
        enabled = followPhoto,
    )

    // I1: host feeds window size in Dp → pure EditorLayoutClass (no Android types in commonMain).
    // ADR-0027: content theme outranks wallpaper for the entire Editor surface.
    ContentEditorThemeHost(
        enabled = followPhoto,
        seedBitmap = seedBitmap,
        seedKey = seedUri,
    ) {
    BoxWithConstraints(modifier = modifier) {
        val layoutClass = remember(maxWidth, maxHeight) {
            editorLayoutClass(maxWidth.value, maxHeight.value)
        }
        EditorScreen(
            imageList = imageList,
            waterMark = waterMark,
            selectedImage = selectedImage,
            templates = templates,
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
                val sel = selectedImage ?: imageList.firstOrNull()
                if (sel != null) {
                    WaterMarkCanvas(
                        modifier = previewModifier,
                        waterMark = waterMark,
                        selectedImage = sel,
                        imageList = imageList,
                        previewImages = previewImages,
                        iconCache = iconCache,
                        onOffsetChanged = onOffsetChanged,
                        onUpdateUriFailed = onUpdateUriFailed,
                    )
                }
            },
            // ADR-0028: ProductThumb → MediaStore Fetcher (not bare content Uri).
            thumbnail = { imageInfo, contentDescription, thumbnailModifier ->
                EditorFilmstripThumb(
                    imageInfo = imageInfo,
                    contentDescription = contentDescription,
                    modifier = thumbnailModifier,
                )
            },
            optionItem = { spec, selected ->
                EditorOptionItem(
                    icon = spec.type.iconPainter(),
                    contentDescription = spec.type.label(),
                    label = spec.type.label(),
                    selected = selected,
                )
            },
            colorOption = { optionModifier, mark, onColor ->
                ColorOption(
                    item = colorModel,
                    waterMark = mark,
                    modifier = optionModifier,
                    onChange = { _, any -> onColor(any as Int) },
                )
            },
            iconOption = { optionModifier, mark, _ ->
                IconOption(
                    waterMark = mark,
                    modifier = optionModifier,
                    onIconPicked = onIconPicked,
                )
            },
            onBack = onBack,
            onAddMoreImages = onAddMoreImages,
            onShowSaveDialog = onShowSaveDialog,
            onGoAboutScreen = onGoAboutScreen,
            onImageSelected = onImageSelected,
            // F2: typed WatermarkConfigChange from shared controls; no FuncType+Any / from().
            onConfigChange = { change -> persistConflator.submit(change) },
            onUseTemplate = onUseTemplate,
            onAddTemplate = onAddTemplate,
            onUpdateTemplate = onUpdateTemplate,
            onDeleteTemplate = onDeleteTemplate,
            modifier = Modifier.fillMaxSize(),
            layoutClass = layoutClass,
            onTemplateSheetVisibilityChange = onTemplateSheetVisibilityChange,
        )
    }
    } // ContentEditorThemeHost
}

/**
 * Filmstrip cell via product Coil path (ADR-0028). MediaStore Fetcher on Android.
 */
@TraceRecomposition(tag = "editor-filmstrip", threshold = 2)
@Composable
private fun EditorFilmstripThumb(
    imageInfo: ImageInfoUi,
    contentDescription: String,
    modifier: Modifier = Modifier,
) {
    ProductAsyncImage(
        thumb = ProductThumb(
            ref = imageInfo.uri,
            maxEdgePx = ProductThumb.UI_THUMB_MAX_EDGE,
        ),
        contentDescription = contentDescription,
        contentScale = ContentScale.Crop,
        modifier = modifier.background(
            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
        ),
    )
}

private fun WaterMark.previewFingerprint(): String = buildString {
    append(markMode)
    append('|').append(text)
    append('|').append(textColor)
    append('|').append(alpha)
    append('|').append(degree)
    append('|').append(hGap)
    append('|').append(vGap)
    append('|').append(textSize)
    append('|').append(textStyle)
    append('|').append(textTypeface)
    append('|').append(tileMode)
    append('|').append(iconUri.value)
}

/**
 * Android editor preview (ADR-0033): wait chrome (filmstrip thumb / empty) or atomic
 * photo + overlay cell. Never paints Source or a baked [AndroidCommonRaster.composeToBitmap]
 * frame in this slot. Path change drops the previous live layers immediately.
 * CLAMP drag updates overlay offset only.
 */
@TraceRecomposition(tag = "editor-preview", threshold = 2)
@Composable
private fun WaterMarkCanvas(
    modifier: Modifier = Modifier,
    waterMark: WaterMark,
    selectedImage: ImageInfoUi,
    imageList: List<ImageInfoUi>,
    previewImages: PreviewImageRepository<Bitmap>,
    iconCache: WatermarkIconCache<Bitmap>,
    onOffsetChanged: (info: ImageInfo) -> Unit,
    onUpdateUriFailed: (SecurityException) -> Unit = { },
) {
    val context = LocalContext.current
    BoxWithConstraints(modifier) {
        val cw = constraints.maxWidth
        val ch = constraints.maxHeight
        val selectedUri = selectedImage.uri.value
        val tileMode = waterMark.obtainTileMode()
        val isClamp = tileMode == Shader.TileMode.CLAMP
        val isTextMode = waterMark.markMode == WatermarkMode.Text
        val wmFp = remember(waterMark) { waterMark.previewFingerprint() }
        val bucket = PreviewResolutionPolicy.committedMaxEdgePx(cw, ch)

        var offsetX by remember(selectedUri) { mutableStateOf(selectedImage.offsetX) }
        var offsetY by remember(selectedUri) { mutableStateOf(selectedImage.offsetY) }

        var livePhoto by remember(selectedUri) { mutableStateOf<ImageBitmap?>(null) }
        var overlay by remember(selectedUri) { mutableStateOf<OverlayCell?>(null) }
        var livePhotoPath by remember(selectedUri) { mutableStateOf<String?>(null) }
        var firstReveal by remember { mutableFloatStateOf(0f) }
        var hasRevealedOnce by remember { mutableStateOf(false) }
        var paintToken by remember { mutableIntStateOf(0) }

        val scope = rememberCoroutineScope()
        val motionPolicy = currentMotionPolicy()
        val firstRevealMs = motionDurationMs(motionPolicy, EwmTheme.motion.firstPreviewRevealMs)

        data class AndroidPreviewPaint(
            val uri: String,
            val image: ImageInfoUi,
            val wm: WaterMark,
            val ox: Float,
            val oy: Float,
            val canvasW: Int,
            val canvasH: Int,
            val pixelBucket: Int,
            val isDraft: Boolean,
            val token: Int,
        )

        val paintHandler = remember {
            mutableStateOf<suspend (AndroidPreviewPaint) -> Unit>({ _ -> })
        }
        val paintConflator = remember {
            DraftRenderConflator<AndroidPreviewPaint>(scope) { req ->
                paintHandler.value(req)
            }
        }
        DisposableEffect(paintConflator) {
            onDispose { paintConflator.close() }
        }

        fun decodeAndroidIcon(wm: WaterMark): Bitmap? {
            if (wm.markMode != WatermarkMode.Image) return null
            if (wm.iconUri.value.isBlank()) {
                error("Image-mode preview requires an icon")
            }
            return iconCache.decoded(wm.iconUri, WatermarkIconCache.ICON_MAX_EDGE_PX) {
                PreviewSourceReuseProbe.recordIconDecode()
                decodeSampledBitmapFromResourceSync(
                    context.contentResolver,
                    wm.iconUri.toUri(),
                    WatermarkIconCache.ICON_MAX_EDGE_PX,
                    WatermarkIconCache.ICON_MAX_EDGE_PX,
                ).data?.bitmap ?: error("Image-mode icon decode failed")
            }
        }

        suspend fun playFirstRevealIfNeeded() {
            if (hasRevealedOnce) {
                firstReveal = 1f
                return
            }
            if (firstRevealMs <= 0) {
                firstReveal = 1f
                hasRevealedOnce = true
                return
            }
            firstReveal = 0f
            val anim = Animatable(0f)
            anim.animateTo(
                1f,
                animationSpec = tween(
                    durationMillis = firstRevealMs,
                    easing = FastOutSlowInEasing,
                ),
            ) {
                firstReveal = value
            }
            firstReveal = 1f
            hasRevealedOnce = true
        }

        fun publishLiveLayers(path: String, photo: ImageBitmap, cell: OverlayCell) {
            val textMode = waterMark.markMode == WatermarkMode.Text
            if (
                !OverlayPreviewPolicy.canPublishLivePhoto(
                    selectedPath = selectedImage.uri.value,
                    photoPath = path,
                    photoWidth = photo.width,
                    cellReadyForWidth = cell.builtForWidth,
                    isTextMode = textMode,
                )
            ) {
                return
            }
            livePhoto = photo
            overlay = cell
            livePhotoPath = path
        }

        fun submitCommittedOverlayPaint(ox: Float, oy: Float) {
            if (cw <= 0 || ch <= 0) return
            paintToken += 1
            paintConflator.submit(
                AndroidPreviewPaint(
                    uri = selectedUri,
                    image = selectedImage,
                    wm = waterMark,
                    ox = ox,
                    oy = oy,
                    canvasW = cw,
                    canvasH = ch,
                    pixelBucket = bucket,
                    isDraft = false,
                    token = paintToken,
                ),
            )
        }

        paintHandler.value = paint@{ req ->
            if (req.canvasW <= 0 || req.canvasH <= 0) return@paint
            if (req.token != paintToken) return@paint
            val srcKey = PreviewKey(req.uri, req.pixelBucket, PreviewPurpose.SourcePlaceholder)
            AndroidPreviewWorkingSet.focusPath = req.uri
            previewImages.applyWorkingSetCapsFromOwner(
                PreviewWorkingSetBudget.caps(
                    longEdgePx = req.pixelBucket,
                    physicalMemoryBytes = Runtime.getRuntime().maxMemory(),
                ),
            )
            if (req.isDraft) {
                overlay = overlay?.withOffset(req.ox, req.oy)
                return@paint
            }
            val base = try {
                previewImages.load(srcKey) {
                    withContext(Dispatchers.IO) {
                        decodePreviewSourceBypassingCache(
                            context.contentResolver,
                            req.image.uri.toUri(),
                            req.canvasW,
                            req.canvasH,
                        ).data?.bitmap
                    }
                }
            } catch (se: SecurityException) {
                withContext(Dispatchers.Main.immediate) { onUpdateUriFailed(se) }
                null
            }
            if (base == null || base.isRecycled) return@paint
            if (selectedImage.uri.value != req.uri) return@paint
            val textMode = req.wm.markMode == WatermarkMode.Text
            val cell = try {
                withContext(Dispatchers.Default) {
                    val icon = decodeAndroidIcon(req.wm)
                    AndroidCommonRaster.composeCell(context, req.wm, base.width, icon)
                }
            } catch (_: Throwable) {
                null
            } ?: return@paint
            val liveOverlay = overlayCellFrom(
                cell = cell,
                config = req.wm,
                offsetX = req.ox,
                offsetY = req.oy,
                builtForWidth = if (textMode) base.width else cell.width,
            )
            if (req.token != paintToken) return@paint
            if (
                !OverlayPreviewPolicy.canPublishLivePhoto(
                    selectedPath = req.uri,
                    photoPath = req.uri,
                    photoWidth = base.width,
                    cellReadyForWidth = liveOverlay.builtForWidth,
                    isTextMode = textMode,
                )
            ) {
                return@paint
            }
            publishLiveLayers(req.uri, base.asImageBitmap(), liveOverlay)
            playFirstRevealIfNeeded()
            if (PreviewSourceReuseProbe.enabled) {
                val snap = PreviewSourceReuseProbe.snapshot()
                val line =
                    "event=publish isDraft=${req.isDraft} path=${req.uri} " +
                        "sourceDecodes=${snap.sourceDecodes} composes=${snap.composes} " +
                        "opens=${snap.contentResolverOpens}"
                android.util.Log.i("PreviewSourceReuse", line)
                runCatching {
                    context.filesDir.resolve("preview-source-reuse-probe.jsonl")
                        .appendText(
                            """{"event":"publish","isDraft":${req.isDraft},"sourceDecodes":${snap.sourceDecodes},"composes":${snap.composes},"opens":${snap.contentResolverOpens}}""" +
                                "\n",
                        )
                }
            }
            prefetchAndroidNeighbors(
                focusUri = req.uri,
                imageList = imageList,
                canvasW = req.canvasW,
                canvasH = req.canvasH,
                bucket = req.pixelBucket,
                previewImages = previewImages,
                context = context,
                token = req.token,
                tokenNow = { paintToken },
            )
        }

        LaunchedEffect(selectedUri, cw, ch, wmFp, bucket) {
            if (cw <= 0 || ch <= 0) return@LaunchedEffect
            // Same-path style ticks keep last LiveLayers until the new cell publishes.
            // remember(selectedUri) already drops the previous photo on path change.
            paintToken += 1
            paintConflator.submit(
                AndroidPreviewPaint(
                    uri = selectedUri,
                    image = selectedImage,
                    wm = waterMark,
                    ox = offsetX,
                    oy = offsetY,
                    canvasW = cw,
                    canvasH = ch,
                    pixelBucket = bucket,
                    isDraft = false,
                    token = paintToken,
                ),
            )
        }

        val chrome = OverlayPreviewPolicy.decide(
            selectedPath = selectedUri,
            photoPath = livePhotoPath,
            photoWidth = livePhoto?.width,
            cellReadyForWidth = overlay?.builtForWidth,
            hasThumb = selectedUri.isNotBlank(),
            isTextMode = isTextMode,
        )
        val photo = if (chrome == OverlayPreviewChrome.LiveLayers) livePhoto else null
        val liveOverlay = if (chrome == OverlayPreviewChrome.LiveLayers) overlay else null

        val dest = if (photo != null && cw > 0 && ch > 0) {
            val scale = min(cw.toFloat() / photo.width, ch.toFloat() / photo.height)
            val drawW = photo.width * scale
            val drawH = photo.height * scale
            ContentRect(
                left = (cw - drawW) / 2f,
                top = (ch - drawH) / 2f,
                width = drawW,
                height = drawH,
            )
        } else {
            null
        }
        val cellDisplay = if (photo != null && liveOverlay != null && dest != null) {
            overlayCellDisplaySize(photo, liveOverlay, cw.toFloat(), ch.toFloat())
        } else {
            null
        }
        val hitDrawW = dest?.width ?: 0f
        val hitDrawH = dest?.height ?: 0f
        val hitLeft = dest?.left ?: 0f
        val hitTop = dest?.top ?: 0f
        val cellW = cellDisplay?.first ?: 0f
        val cellH = cellDisplay?.second ?: 0f
        val identityLive = chrome == OverlayPreviewChrome.LiveLayers &&
            livePhotoPath == selectedUri
        val hitReady = !isClamp || (cellW > 0f && cellH > 0f)

        val canvasModifier = if (isClamp && identityLive && hitReady) {
            Modifier
                .fillMaxSize()
                .pointerInput(hitDrawW, hitDrawH, hitLeft, hitTop, cellW, cellH, selectedUri) {
                    var draggingWatermark = false
                    detectDragGestures(
                        onDragStart = { start ->
                            draggingWatermark = isTouchingClampWatermark(
                                pointer = start,
                                left = hitLeft,
                                top = hitTop,
                                regionWidth = hitDrawW,
                                regionHeight = hitDrawH,
                                offsetX = offsetX,
                                offsetY = offsetY,
                                cellWidth = cellW,
                                cellHeight = cellH,
                            )
                        },
                        onDragEnd = {
                            if (draggingWatermark) {
                                if (isClampWatermarkOutOfDrawable(
                                        offsetX, offsetY, hitDrawW, hitDrawH, cellW, cellH,
                                    )
                                ) {
                                    val startX = offsetX
                                    val startY = offsetY
                                    val centerX = ((hitDrawW - cellW) / 2f) / hitDrawW
                                    val centerY = ((hitDrawH - cellH) / 2f) / hitDrawH
                                    onOffsetChanged(
                                        selectedImage
                                            .copy(offsetX = centerX, offsetY = centerY)
                                            .toImageInfo(),
                                    )
                                    submitCommittedOverlayPaint(centerX, centerY)
                                    scope.launch {
                                        Animatable(0f).animateTo(
                                            1f,
                                            animationSpec = tween(durationMillis = 300),
                                        ) {
                                            offsetX = startX + (centerX - startX) * value
                                            offsetY = startY + (centerY - startY) * value
                                            overlay = overlay?.withOffset(offsetX, offsetY)
                                        }
                                    }
                                } else {
                                    onOffsetChanged(
                                        selectedImage
                                            .copy(offsetX = offsetX, offsetY = offsetY)
                                            .toImageInfo(),
                                    )
                                    submitCommittedOverlayPaint(offsetX, offsetY)
                                }
                            }
                            draggingWatermark = false
                        },
                        onDragCancel = { draggingWatermark = false },
                    ) { change, drag ->
                        if (draggingWatermark) {
                            change.consume()
                            offsetX += drag.x / hitDrawW
                            offsetY += drag.y / hitDrawH
                            overlay = overlay?.withOffset(offsetX, offsetY)
                        }
                    }
                }
        } else {
            Modifier.fillMaxSize()
        }

        val reveal = if (chrome == OverlayPreviewChrome.LiveLayers) {
            firstReveal.coerceIn(0f, 1f)
        } else {
            1f
        }
        LiveOverlayPreview(
            chrome = chrome,
            photo = photo,
            overlay = liveOverlay,
            waitThumb = if (chrome == OverlayPreviewChrome.WaitThumb) {
                { thumbMod ->
                    ProductAsyncImage(
                        thumb = ProductThumb(
                            ref = selectedImage.uri,
                            maxEdgePx = ProductThumb.UI_THUMB_MAX_EDGE,
                        ),
                        contentDescription = "Watermark preview",
                        contentScale = ContentScale.Fit,
                        modifier = thumbMod,
                    )
                }
            } else {
                null
            },
            modifier = canvasModifier.graphicsLayer { alpha = reveal },
        )
    }
}

/** Content rect for FIT_CENTER of a photo inside canvas [cw]×[ch]. */
private data class ContentRect(
    val left: Float,
    val top: Float,
    val width: Float,
    val height: Float,
)

private suspend fun prefetchAndroidNeighbors(
    focusUri: String,
    imageList: List<ImageInfoUi>,
    canvasW: Int,
    canvasH: Int,
    bucket: Int,
    previewImages: PreviewImageRepository<Bitmap>,
    context: android.content.Context,
    token: Int,
    tokenNow: () -> Int,
) {
    val idx = imageList.indexOfFirst { it.uri.value == focusUri }
    if (idx < 0) return
    withContext(Dispatchers.Default) {
        for (i in neighborIndices(idx, imageList.size)) {
            if (tokenNow() != token) return@withContext
            val info = imageList[i]
            val path = info.uri.value
            if (path.isBlank()) continue
            val srcKey = PreviewKey(path, bucket, PreviewPurpose.SourcePlaceholder)
            if (previewImages.peekCached(srcKey) != null) continue
            runCatching {
                previewImages.load(srcKey) {
                    // completionScope inherits the owner (Main) dispatcher — decoding here
                    // without a hop blocks the UI thread and janks filmstrip switches.
                    withContext(Dispatchers.IO) {
                        try {
                            decodePreviewSourceBypassingCache(
                                context.contentResolver,
                                info.uri.toUri(),
                                canvasW,
                                canvasH,
                            ).data?.bitmap
                        } catch (_: SecurityException) {
                            null
                        }
                    }
                }
            }
        }
    }
}

private fun isTouchingClampWatermark(
    pointer: Offset,
    left: Float,
    top: Float,
    regionWidth: Float,
    regionHeight: Float,
    offsetX: Float,
    offsetY: Float,
    cellWidth: Float,
    cellHeight: Float,
): Boolean {
    val waterMarkX = left + offsetX * regionWidth
    val waterMarkY = top + offsetY * regionHeight
    return pointer.x > waterMarkX &&
        pointer.x < waterMarkX + cellWidth &&
        pointer.y > waterMarkY &&
        pointer.y < waterMarkY + cellHeight
}

private fun isClampWatermarkOutOfDrawable(
    offsetX: Float,
    offsetY: Float,
    regionWidth: Float,
    regionHeight: Float,
    cellWidth: Float,
    cellHeight: Float,
): Boolean {
    val waterMarkX = offsetX * regionWidth
    val waterMarkY = offsetY * regionHeight
    return waterMarkX + cellWidth < 0f ||
        waterMarkX > regionWidth ||
        waterMarkY > regionHeight ||
        waterMarkY + cellHeight < 0f
}
