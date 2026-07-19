package me.rosuh.easywatermark.ui

import android.content.ContentUris
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Shader
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.util.Size as AndroidSize
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
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
import me.rosuh.easywatermark.data.model.FuncTitleModel
import me.rosuh.easywatermark.data.model.FuncType
import me.rosuh.easywatermark.data.model.ImageInfo
import me.rosuh.easywatermark.data.model.WaterMark
import me.rosuh.easywatermark.data.model.WatermarkMode
import me.rosuh.easywatermark.data.model.entity.Template
import me.rosuh.easywatermark.render.AndroidCommonRaster
import me.rosuh.easywatermark.ui.compose.ColorOption
import me.rosuh.easywatermark.ui.compose.IconOption
import me.rosuh.easywatermark.utils.bitmap.decodeSampledBitmapFromResource
import me.rosuh.easywatermark.utils.bitmap.decodeSampledBitmapFromResourceSync
import me.rosuh.easywatermark.utils.ktx.obtainTileMode
import me.rosuh.easywatermark.utils.ktx.toUri
import kotlin.math.abs
import kotlin.math.min

/**
 * Android host for shared [me.rosuh.easywatermark.ui.EditorScreen].
 * Named AndroidEditorScreen (file AndroidEditorScreen.kt) to avoid JVM clash with shared EditorScreenKt.
 * Supplies resources, Coil thumbnails, Color/Icon edges, and [WaterMarkCanvas] preview.
 */
@Composable
fun AndroidEditorScreen(
    imageList: List<ImageInfo>,
    waterMark: WaterMark,
    onBack: () -> Unit,
    onOffsetChanged: (ImageInfo) -> Unit,
    modifier: Modifier = Modifier,
    selectedImage: ImageInfo? = null,
    onImageSelected: (ImageInfo) -> Unit = {},
    onWaterMrkChange: (item: FuncTitleModel, any: Any) -> Unit = { _, _ -> },
    onIconPicked: (Uri) -> Unit = {},
    onAddMoreImages: () -> Unit = { },
    onShowSaveDialog: () -> Unit = { },
    onGoAboutScreen: () -> Unit = { },
    templates: List<Template> = emptyList(),
    onUseTemplate: (Template) -> Unit = {},
    onAddTemplate: (String) -> Unit = {},
    onUpdateTemplate: (Template) -> Unit = {},
    onDeleteTemplate: (Template) -> Unit = {},
) {
    val colorModel = remember { FuncTitleModel(FuncType.Color) }

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
                    onOffsetChanged = onOffsetChanged,
                )
            }
        },
        // Use the same MediaStore/EXIF decode path as the big preview — Coil fails blank on
        // some content URIs / HEIC / EXIF-odd files that BitmapUtils still decodes fine.
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
        onConfigChange = { type, value ->
            onWaterMrkChange(androidOptionModel(type), value)
        },
        onUseTemplate = onUseTemplate,
        onAddTemplate = onAddTemplate,
        onUpdateTemplate = onUpdateTemplate,
        onDeleteTemplate = onDeleteTemplate,
        modifier = modifier,
    )
}

private fun androidOptionModel(type: FuncType): FuncTitleModel = FuncTitleModel(type)

private const val FilmstripThumbPx = 160

/**
 * Filmstrip cell: MediaStore system thumb → BitmapUtils (EXIF) fallback.
 * Avoids Coil content-URI blanks that still decode fine for the main preview.
 */
@Composable
private fun EditorFilmstripThumb(
    imageInfo: ImageInfo,
    contentDescription: String,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val uriValue = imageInfo.uri.value
    val bitmap by produceState<Bitmap?>(initialValue = null, uriValue) {
        value = withContext(Dispatchers.IO) {
            loadFilmstripThumbBitmap(context, imageInfo.uri.toUri(), FilmstripThumbPx)
        }
    }
    val bmp = bitmap
    if (bmp != null && !bmp.isRecycled) {
        Image(
            bitmap = bmp.asImageBitmap(),
            contentDescription = contentDescription,
            contentScale = ContentScale.Crop,
            modifier = modifier,
        )
    } else {
        Box(
            modifier = modifier.background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f)),
        )
    }
}

/**
 * Prefer system MediaStore thumbnails (fast, correct for gallery content URIs).
 * Fall back to the same EXIF-aware decoder used by [WaterMarkCanvas].
 */
private fun loadFilmstripThumbBitmap(context: Context, uri: Uri, sizePx: Int): Bitmap? {
    val size = sizePx.coerceIn(64, 320)
    // 1) MediaStore / ContentResolver thumbnail (API 29+)
    if (Build.VERSION.SDK_INT >= 29) {
        try {
            val thumb = context.contentResolver.loadThumbnail(uri, AndroidSize(size, size), null)
            if (thumb != null && !thumb.isRecycled) return thumb
        } catch (_: Exception) {
            // fall through
        }
    } else {
        try {
            @Suppress("DEPRECATION")
            val id = ContentUris.parseId(uri)
            val opts = BitmapFactory.Options().apply {
                inPreferredConfig = Bitmap.Config.RGB_565
            }
            @Suppress("DEPRECATION")
            val thumb = MediaStore.Images.Thumbnails.getThumbnail(
                context.contentResolver,
                id,
                MediaStore.Images.Thumbnails.MINI_KIND,
                opts,
            )
            if (thumb != null && !thumb.isRecycled) return thumb
        } catch (_: Exception) {
            // fall through
        }
    }
    // 2) Full decode path with EXIF (same as preview) — subsampled to thumb size.
    return try {
        decodeSampledBitmapFromResourceSync(
            context.contentResolver,
            uri,
            size,
            size,
        ).data?.bitmap
    } catch (_: Exception) {
        null
    }
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
@Composable
private fun WaterMarkCanvas(
    modifier: Modifier = Modifier,
    waterMark: WaterMark,
    selectedImage: ImageInfo,
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

        var offsetX by remember(selectedUri) { mutableStateOf(selectedImage.offsetX) }
        var offsetY by remember(selectedUri) { mutableStateOf(selectedImage.offsetY) }

        var displayed by remember { mutableStateOf<PreviewFrame?>(null) }
        var incoming by remember { mutableStateOf<PreviewFrame?>(null) }
        var crossfade by remember { mutableFloatStateOf(1f) }
        // Last decoded base (no watermark) for CLAMP drag re-compose without re-open ContentResolver.
        var baseCache by remember { mutableStateOf<Pair<String, Bitmap>?>(null) }
        // Main load already baked current offsets — skip one redundant offset re-bake.
        var suppressOffsetRebake by remember { mutableStateOf(false) }

        var clampCellSize by remember { mutableStateOf<Pair<Float, Float>?>(null) }

        val imagePaint = remember { Paint(Paint.FILTER_BITMAP_FLAG) }
        val imageMatrix = remember { Matrix() }
        val scope = rememberCoroutineScope()

        // --- Load pipeline: cancel previous on key change via LaunchedEffect cancellation ---
        LaunchedEffect(selectedUri, cw, ch, wmFp) {
            if (cw <= 0 || ch <= 0) return@LaunchedEffect
            val requestUri = selectedUri
            val requestImage = selectedImage
            val requestWm = waterMark

            val base = withContext(Dispatchers.IO) {
                try {
                    decodeSampledBitmapFromResource(
                        context.contentResolver,
                        requestImage.uri.toUri(),
                        cw,
                        ch,
                    ).data?.bitmap
                } catch (se: SecurityException) {
                    withContext(Dispatchers.Main.immediate) { onUpdateUriFailed(se) }
                    null
                }
            }
            ensureActive()
            // Selection may have moved on; discard.
            if (selectedImage.uri.value != requestUri) return@LaunchedEffect
            if (base == null || base.isRecycled) return@LaunchedEffect
            baseCache = requestUri to base

            val composed = withContext(Dispatchers.Default) {
                try {
                    val info = requestImage.copy(offsetX = offsetX, offsetY = offsetY).also {
                        it.width = base.width
                        it.height = base.height
                    }
                    val icon = if (requestWm.markMode == WatermarkMode.Image) {
                        decodeSampledBitmapFromResource(
                            context.contentResolver,
                            requestWm.iconUri.toUri(),
                            base.width,
                            base.height,
                        ).data?.bitmap
                    } else {
                        null
                    }
                    AndroidCommonRaster.composeToBitmap(context, base, requestWm, info, icon)
                } catch (_: Throwable) {
                    null
                }
            }
            ensureActive()
            if (selectedImage.uri.value != requestUri) return@LaunchedEffect
            val frame = if (composed != null && !composed.isRecycled) {
                PreviewFrame(requestUri, composed)
            } else {
                PreviewFrame(requestUri, base)
            }
            if (isClamp) {
                clampCellSize = withContext(Dispatchers.Default) {
                    try {
                        val info = requestImage.copy(offsetX = 0.5f, offsetY = 0.5f).also {
                            it.width = base.width
                            it.height = base.height
                        }
                        val icon = if (requestWm.markMode == WatermarkMode.Image) {
                            decodeSampledBitmapFromResource(
                                context.contentResolver,
                                requestWm.iconUri.toUri(),
                                base.width,
                                base.height,
                            ).data?.bitmap
                        } else {
                            null
                        }
                        val cellProbe =
                            AndroidCommonRaster.cellSizePx(context, requestWm, info, icon)
                        val scale = min(cw.toFloat() / base.width, ch.toFloat() / base.height)
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

            ensureActive()
            if (selectedImage.uri.value != requestUri) return@LaunchedEffect

            val current = displayed
            if (current == null || current.uriValue == requestUri) {
                // First frame or same-uri refresh (watermark config) — no crossfade.
                displayed = frame
                incoming = null
                crossfade = 1f
                suppressOffsetRebake = true
            } else {
                // Different source image: morph bounds + crossfade (aspect-aware duration).
                incoming = frame
                crossfade = 0f
                val fromAspect = current.bitmap.width.toFloat() / current.bitmap.height.coerceAtLeast(1)
                val toAspect = frame.bitmap.width.toFloat() / frame.bitmap.height.coerceAtLeast(1)
                val aspectDelta = abs(fromAspect - toAspect) / maxOf(fromAspect, toAspect, 0.01f)
                val duration = (
                    PreviewCrossfadeMinMs +
                        (PreviewCrossfadeMaxMs - PreviewCrossfadeMinMs) * aspectDelta.coerceIn(0f, 1f)
                    ).toInt()
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
                ensureActive()
                if (selectedImage.uri.value == requestUri) {
                    displayed = frame
                    incoming = null
                    crossfade = 1f
                    suppressOffsetRebake = true
                }
            }
        }

        // CLAMP: offset drag re-bakes watermark without crossfade / re-decode base.
        LaunchedEffect(offsetX, offsetY, selectedUri, isClamp, wmFp, baseCache?.first) {
            if (!isClamp) return@LaunchedEffect
            if (suppressOffsetRebake) {
                suppressOffsetRebake = false
                return@LaunchedEffect
            }
            val shown = displayed
            if (shown == null || shown.uriValue != selectedUri) return@LaunchedEffect
            if (incoming != null) return@LaunchedEffect
            val cached = baseCache
            if (cached == null || cached.first != selectedUri || cached.second.isRecycled) {
                return@LaunchedEffect
            }
            val requestUri = selectedUri
            val requestImage = selectedImage
            val requestWm = waterMark
            val base = cached.second
            val composed = withContext(Dispatchers.Default) {
                try {
                    val info = requestImage.copy(offsetX = offsetX, offsetY = offsetY).also {
                        it.width = base.width
                        it.height = base.height
                    }
                    val icon = if (requestWm.markMode == WatermarkMode.Image) {
                        decodeSampledBitmapFromResource(
                            context.contentResolver,
                            requestWm.iconUri.toUri(),
                            base.width,
                            base.height,
                        ).data?.bitmap
                    } else {
                        null
                    }
                    AndroidCommonRaster.composeToBitmap(context, base, requestWm, info, icon)
                } catch (_: Throwable) {
                    null
                }
            } ?: return@LaunchedEffect
            ensureActive()
            if (selectedImage.uri.value != requestUri) return@LaunchedEffect
            displayed = PreviewFrame(requestUri, composed)
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
                                        selectedImage.copy(offsetX = centerX, offsetY = centerY),
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
                                        selectedImage.copy(offsetX = offsetX, offsetY = offsetY),
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

                if (inc == null) {
                    drawBitmapInBox(
                        frame = disp,
                        boxL = boxLeft,
                        boxT = boxTop,
                        boxWidth = boxW,
                        boxHeight = boxH,
                        alpha = 1f,
                    )
                } else {
                    // Morph shared box + crossfade both images inside it (smooth aspect change).
                    drawBitmapInBox(
                        frame = disp,
                        boxL = boxLeft,
                        boxT = boxTop,
                        boxWidth = boxW,
                        boxHeight = boxH,
                        alpha = 1f - t,
                    )
                    drawBitmapInBox(
                        frame = inc,
                        boxL = boxLeft,
                        boxT = boxTop,
                        boxWidth = boxW,
                        boxHeight = boxH,
                        alpha = t,
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

private fun lerp(a: Float, b: Float, t: Float): Float = a + (b - a) * t

/** Aspect-similar switches stay snappy; large aspect deltas get a longer morph. */
private const val PreviewCrossfadeMinMs = 180
private const val PreviewCrossfadeMaxMs = 320

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
