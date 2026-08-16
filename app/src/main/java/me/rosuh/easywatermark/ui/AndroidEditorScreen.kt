package me.rosuh.easywatermark.ui

import android.graphics.Bitmap
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Shader
import android.net.Uri
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
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
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
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
import me.rosuh.easywatermark.render.PreviewKey
import me.rosuh.easywatermark.render.PreviewPaintPolicy
import me.rosuh.easywatermark.render.PreviewPurpose
import me.rosuh.easywatermark.render.PreviewResolutionPolicy
import me.rosuh.easywatermark.render.PreviewSourceReuseProbe
import me.rosuh.easywatermark.render.PreviewWorkingSetBudget
import me.rosuh.easywatermark.render.WatermarkIconCache
import me.rosuh.easywatermark.render.neighborIndices
import me.rosuh.easywatermark.ui.DraftRenderConflator
import me.rosuh.easywatermark.ui.compose.ColorOption
import me.rosuh.easywatermark.ui.compose.IconOption
import me.rosuh.easywatermark.ui.image.ProductAsyncImage
import me.rosuh.easywatermark.ui.image.ProductThumb
import me.rosuh.easywatermark.ui.image.rememberProductThumbBitmap
import me.rosuh.easywatermark.ui.theme.ContentEditorThemeHost
import me.rosuh.easywatermark.ui.theme.EwmTheme
import me.rosuh.easywatermark.ui.theme.currentMotionPolicy
import me.rosuh.easywatermark.ui.theme.motionDurationMs
import me.rosuh.easywatermark.ui.theme.previewCrossfadeDurationMs
import me.rosuh.easywatermark.utils.bitmap.decodePreviewSourceBypassingCache
import me.rosuh.easywatermark.utils.bitmap.decodeSampledBitmapFromResourceSync
import me.rosuh.easywatermark.utils.ktx.obtainTileMode
import me.rosuh.easywatermark.utils.ktx.toUri
import kotlin.math.abs
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

/**
 * Identity-safe preview frame. Canvas may keep drawing a previous [uriValue] until the next
 * Frame is ready (no black flash), then crossfades. Never mixes uri A pixels with uri B layout. */
private data class PreviewFrame(
    val uriValue: String,
    val bitmap: Bitmap,
)

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
 * Android watermark preview canvas.
 *
 * - Keep previous frame while loading the next (no black flash, no wrong-uri layout mix)
 * - Bakes watermark via commonMain [AndroidCommonRaster] before commit (ADR-0018 production path)
 * - Crossfade between frames when the source uri changes
 * - CLAMP drag updates local offset, re-bakes, and commits via [onOffsetChanged] (required)
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
        val wmFp = remember(waterMark) { waterMark.previewFingerprint() }
        val bucket = PreviewResolutionPolicy.committedMaxEdgePx(cw, ch)

        var offsetX by remember(selectedUri) { mutableStateOf(selectedImage.offsetX) }
        var offsetY by remember(selectedUri) { mutableStateOf(selectedImage.offsetY) }

        var displayed by remember { mutableStateOf<PreviewFrame?>(null) }
        var incoming by remember { mutableStateOf<PreviewFrame?>(null) }
        var crossfade by remember { mutableFloatStateOf(1f) }
        var firstReveal by remember { mutableFloatStateOf(0f) }
        var hasRevealedOnce by remember { mutableStateOf(false) }
        var suppressOffsetRebake by remember { mutableStateOf(false) }
        var paintToken by remember { mutableIntStateOf(0) }

        var clampCellSize by remember { mutableStateOf<Pair<Float, Float>?>(null) }

        val imagePaint = remember { Paint(Paint.FILTER_BITMAP_FLAG) }
        val imageMatrix = remember { Matrix() }
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

        suspend fun publishAndroidFrame(
            requestUri: String,
            frame: PreviewFrame,
            playCrossfade: Boolean,
        ) {
            val current = displayed
            if (current == null || current.uriValue == requestUri || !playCrossfade) {
                displayed = frame
                incoming = null
                crossfade = 1f
                suppressOffsetRebake = true
                if (!hasRevealedOnce) {
                    if (firstRevealMs <= 0) {
                        firstReveal = 1f
                        hasRevealedOnce = true
                    } else {
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
                } else {
                    firstReveal = 1f
                }
            } else {
                incoming = frame
                crossfade = 0f
                firstReveal = 1f
                hasRevealedOnce = true
                val fromAspect = current.bitmap.width.toFloat() / current.bitmap.height.coerceAtLeast(1)
                val toAspect = frame.bitmap.width.toFloat() / frame.bitmap.height.coerceAtLeast(1)
                val aspectDelta = abs(fromAspect - toAspect) / maxOf(fromAspect, toAspect, 0.01f)
                val duration = previewCrossfadeDurationMs(motionPolicy, aspectDelta)
                if (duration <= 0) {
                    displayed = frame
                    incoming = null
                    crossfade = 1f
                    suppressOffsetRebake = true
                } else {
                    val anim = Animatable(0f)
                    anim.animateTo(
                        1f,
                        animationSpec = tween(
                            durationMillis = duration,
                            easing = FastOutSlowInEasing,
                        ),
                    ) {
                        crossfade = value
                    }
                    if (selectedImage.uri.value == requestUri) {
                        displayed = frame
                        incoming = null
                        crossfade = 1f
                        suppressOffsetRebake = true
                    }
                }
            }
        }

        paintHandler.value = paint@{ req ->
            if (req.canvasW <= 0 || req.canvasH <= 0) return@paint
            if (req.token != paintToken && req.isDraft) return@paint
            val srcKey = PreviewKey(req.uri, req.pixelBucket, PreviewPurpose.SourcePlaceholder)
            val wmKey = PreviewKey(req.uri, req.pixelBucket, PreviewPurpose.Watermarked)
            AndroidPreviewWorkingSet.focusPath = req.uri
            previewImages.applyWorkingSetCapsFromOwner(
                PreviewWorkingSetBudget.caps(
                    longEdgePx = req.pixelBucket,
                    physicalMemoryBytes = Runtime.getRuntime().maxMemory(),
                ),
            )
            if (!req.isDraft) {
                val hit = previewImages.peekCached(wmKey) ?: previewImages.cached(wmKey)
                if (hit != null && !hit.isRecycled) {
                    publishAndroidFrame(req.uri, PreviewFrame(req.uri, hit), playCrossfade = false)
                    return@paint
                }
                previewImages.peekCached(srcKey)?.takeUnless { it.isRecycled }?.let { source ->
                    if (PreviewPaintPolicy.showSourceWhileComposing(displayed?.uriValue, req.uri)) {
                        displayed = PreviewFrame(req.uri, source)
                    }
                }
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
            if (selectedImage.uri.value != req.uri && !req.isDraft) return@paint
            val composed = try {
                withContext(Dispatchers.Default) {
                    val info = req.image.toImageInfo().copy(
                        offsetX = req.ox,
                        offsetY = req.oy,
                    ).also {
                        it.width = base.width
                        it.height = base.height
                    }
                    val icon = decodeAndroidIcon(req.wm)
                    AndroidCommonRaster.composeToBitmap(context, base, req.wm, info, icon)
                }
            } catch (_: Throwable) {
                null
            }
            if (composed == null) return@paint
            if (!req.isDraft) {
                previewImages.load(wmKey) { composed }
            }
            if (isClamp) {
                clampCellSize = withContext(Dispatchers.Default) {
                    try {
                        val info = req.image.toImageInfo().copy(
                            offsetX = 0.5f,
                            offsetY = 0.5f,
                        ).also {
                            it.width = base.width
                            it.height = base.height
                        }
                        val icon = decodeAndroidIcon(req.wm)
                        val cellProbe = AndroidCommonRaster.cellSizePx(context, req.wm, info, icon)
                        val scale = min(req.canvasW.toFloat() / base.width, req.canvasH.toFloat() / base.height)
                        val drawW = base.width * scale
                        val drawH = base.height * scale
                        (cellProbe.first * drawW / base.width) to
                            (cellProbe.second * drawH / base.height)
                    } catch (_: Throwable) {
                        null
                    }
                }
            } else {
                clampCellSize = null
            }
            publishAndroidFrame(
                requestUri = req.uri,
                frame = PreviewFrame(req.uri, composed),
                playCrossfade = !req.isDraft && displayed?.uriValue != req.uri,
            )
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
            if (!req.isDraft) {
                prefetchAndroidNeighbors(
                    focusUri = req.uri,
                    imageList = imageList,
                    waterMark = req.wm,
                    canvasW = req.canvasW,
                    canvasH = req.canvasH,
                    bucket = req.pixelBucket,
                    previewImages = previewImages,
                    decodeIcon = { decodeAndroidIcon(it) },
                    context = context,
                    token = req.token,
                    tokenNow = { paintToken },
                )
            }
        }

        var lastWmFp by remember { mutableStateOf<String?>(null) }
        LaunchedEffect(selectedUri, cw, ch, wmFp, bucket) {
            if (cw <= 0 || ch <= 0) return@LaunchedEffect
            if (lastWmFp != null && lastWmFp != wmFp) {
                previewImages.clearPurpose(PreviewPurpose.Watermarked)
            }
            lastWmFp = wmFp
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

        LaunchedEffect(offsetX, offsetY, selectedUri, isClamp) {
            if (!isClamp) return@LaunchedEffect
            if (suppressOffsetRebake) {
                suppressOffsetRebake = false
                return@LaunchedEffect
            }
            if (displayed == null || incoming != null) return@LaunchedEffect
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
                    isDraft = true,
                    token = paintToken,
                ),
            )
        }

        val disp = displayed
        val inc = incoming
        if (disp == null || cw <= 0 || ch <= 0) {
            // Keep empty only before the very first frame; no black flash on later switches.
            return@BoxWithConstraints
        }

        val t = FastOutSlowInEasing.transform(crossfade.coerceIn(0f, 1f))
        val fromRect = naturalContentRect(disp.bitmap, cw, ch)
        val toRect = if (inc != null) {
            naturalContentRect(inc.bitmap, cw, ch)
        } else {
            fromRect
        }
        // Shared morphing viewport: bounds lerp old→new so portrait↔landscape doesn't hard-cut.
        val boxLeft = lerp(fromRect.left, toRect.left, t)
        val boxTop = lerp(fromRect.top, toRect.top, t)
        val boxW = lerp(fromRect.width, toRect.width, t)
        val boxH = lerp(fromRect.height, toRect.height, t)

        val hitDrawW = boxW
        val hitDrawH = boxH
        val hitLeft = boxLeft
        val hitTop = boxTop

        val cellW = clampCellSize?.first ?: hitDrawW
        val cellH = clampCellSize?.second ?: hitDrawH
        val hitReady = !isClamp || (cellW > 0f && cellH > 0f)
        // Only allow clamp drag when displayed identity matches selection (not mid-stale).
        val identityLive = disp.uriValue == selectedUri ||
            (inc != null && inc.uriValue == selectedUri)

        val canvasModifier = if (isClamp && identityLive && hitReady && inc == null) {
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
                                    // Commit session state immediately (export must not see a
                                    // 300ms stale-offset window). Animation is local visual only.
                                    onOffsetChanged(
                                        selectedImage
                                            .copy(offsetX = centerX, offsetY = centerY)
                                            .toImageInfo(),
                                    )
                                    scope.launch {
                                        Animatable(0f).animateTo(
                                            1f,
                                            animationSpec = tween(durationMillis = 300),
                                        ) {
                                            offsetX = startX + (centerX - startX) * value
                                            offsetY = startY + (centerY - startY) * value
                                        }
                                    }
                                } else {
                                    onOffsetChanged(
                                        selectedImage
                                            .copy(offsetX = offsetX, offsetY = offsetY)
                                            .toImageInfo(),
                                    )
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
                        }
                    }
                }
        } else {
            Modifier.fillMaxSize()
        }

        Canvas(modifier = canvasModifier) {
            drawIntoCanvas { canvas ->
                val nc = canvas.nativeCanvas

                /** FIT_CENTER [bitmap] into the given content box (watermark already baked when possible). */
                fun drawBitmapInBox(
                    frame: PreviewFrame,
                    boxL: Float,
                    boxT: Float,
                    boxWidth: Float,
                    boxHeight: Float,
                    alpha: Float,
                ) {
                    if (frame.bitmap.isRecycled || boxWidth <= 0f || boxHeight <= 0f) return
                    val scale = min(
                        boxWidth / frame.bitmap.width,
                        boxHeight / frame.bitmap.height,
                    )
                    val drawW = frame.bitmap.width * scale
                    val drawH = frame.bitmap.height * scale
                    val left = boxL + (boxWidth - drawW) / 2f
                    val top = boxT + (boxHeight - drawH) / 2f
                    imageMatrix.apply {
                        reset()
                        postScale(scale, scale)
                        postTranslate(left, top)
                    }
                    imagePaint.alpha = (alpha.coerceIn(0f, 1f) * 255f).toInt()
                    nc.drawBitmap(frame.bitmap, imageMatrix, imagePaint)
                }

                val reveal = firstReveal.coerceIn(0f, 1f)
                if (inc == null) {
                    drawBitmapInBox(
                        frame = disp,
                        boxL = boxLeft,
                        boxT = boxTop,
                        boxWidth = boxW,
                        boxHeight = boxH,
                        alpha = reveal,
                    )
                } else {
                    // Morph shared box + crossfade both images inside it (smooth aspect change).
                    drawBitmapInBox(
                        frame = disp,
                        boxL = boxLeft,
                        boxT = boxTop,
                        boxWidth = boxW,
                        boxHeight = boxH,
                        alpha = (1f - t) * reveal,
                    )
                    drawBitmapInBox(
                        frame = inc,
                        boxL = boxLeft,
                        boxT = boxTop,
                        boxWidth = boxW,
                        boxHeight = boxH,
                        alpha = t * reveal,
                    )
                }
                imagePaint.alpha = 255
            }
        }
    }
}

/** Content rect for FIT_CENTER of [bitmap] inside canvas [cw]×[ch]. */
private data class ContentRect(
    val left: Float,
    val top: Float,
    val width: Float,
    val height: Float,
)

private fun naturalContentRect(bitmap: Bitmap, cw: Int, ch: Int): ContentRect {
    val scale = min(cw.toFloat() / bitmap.width, ch.toFloat() / bitmap.height)
    val drawW = bitmap.width * scale
    val drawH = bitmap.height * scale
    return ContentRect(
        left = (cw - drawW) / 2f,
        top = (ch - drawH) / 2f,
        width = drawW,
        height = drawH,
    )
}

private suspend fun prefetchAndroidNeighbors(
    focusUri: String,
    imageList: List<ImageInfoUi>,
    waterMark: WaterMark,
    canvasW: Int,
    canvasH: Int,
    bucket: Int,
    previewImages: PreviewImageRepository<Bitmap>,
    decodeIcon: (WaterMark) -> Bitmap?,
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
            val wmKey = PreviewKey(path, bucket, PreviewPurpose.Watermarked)
            if (previewImages.peekCached(wmKey) != null) continue
            runCatching {
                val source = previewImages.load(
                    PreviewKey(path, bucket, PreviewPurpose.SourcePlaceholder),
                ) {
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
                } ?: return@runCatching
                previewImages.load(wmKey) {
                    val composedInfo = info.toImageInfo().copy(
                        offsetX = info.offsetX,
                        offsetY = info.offsetY,
                    ).also {
                        it.width = source.width
                        it.height = source.height
                    }
                    AndroidCommonRaster.composeToBitmap(
                        context,
                        source,
                        waterMark,
                        composedInfo,
                        decodeIcon(waterMark),
                    )
                }
            }
        }
    }
}

private fun lerp(a: Float, b: Float, t: Float): Float = a + (b - a) * t

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
