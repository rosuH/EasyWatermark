package me.rosuh.easywatermark.ui

import android.graphics.Bitmap
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Shader
import android.text.TextPaint
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.request.crossfade
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.rosuh.easywatermark.data.model.FuncTitleModel
import me.rosuh.easywatermark.data.model.FuncType
import me.rosuh.easywatermark.data.model.ImageInfo
import me.rosuh.easywatermark.data.model.WaterMark
import me.rosuh.easywatermark.data.model.WatermarkMode
import me.rosuh.easywatermark.data.model.entity.Template
import me.rosuh.easywatermark.render.AndroidCommonRaster
import me.rosuh.easywatermark.render.CommonRasterFlags
import me.rosuh.easywatermark.render.WatermarkRenderer
import me.rosuh.easywatermark.render.androidTextMeasureEnv
import me.rosuh.easywatermark.ui.compose.ColorOption
import me.rosuh.easywatermark.ui.compose.IconOption
import me.rosuh.easywatermark.ui.widget.utils.WaterMarkShader
import me.rosuh.easywatermark.utils.bitmap.decodeSampledBitmapFromResource
import me.rosuh.easywatermark.utils.ktx.applyConfig
import me.rosuh.easywatermark.utils.ktx.obtainTileMode
import me.rosuh.easywatermark.utils.ktx.toMediaRef
import me.rosuh.easywatermark.utils.ktx.toUri
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
    modifier: Modifier = Modifier,
    selectedImage: ImageInfo? = null,
    onBack: () -> Unit,
    onImageSelected: (ImageInfo) -> Unit = {},
    onWaterMrkChange: (item: FuncTitleModel, any: Any) -> Unit = { _, _ -> },
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
    val iconModel = remember { FuncTitleModel(FuncType.Icon) }

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
                )
            }
        },
        thumbnail = { imageInfo, contentDescription, thumbnailModifier ->
            AsyncImage(
                model = ImageRequest.Builder(LocalContext.current)
                    .data(imageInfo.uri.toUri())
                    .crossfade(true)
                    .build(),
                contentDescription = contentDescription,
                contentScale = ContentScale.Crop,
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
        iconOption = { optionModifier, mark, onIcon ->
            IconOption(
                item = iconModel,
                waterMark = mark,
                modifier = optionModifier,
                onIconSelected = { _, ref -> onIcon(ref) },
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

/**
 * Android watermark preview canvas (platform paint edge for shared [EditorScreen]).
 */
@Composable
private fun WaterMarkCanvas(
    modifier: Modifier = Modifier,
    waterMark: WaterMark,
    selectedImage: ImageInfo,
    onOffsetChanged: (info: ImageInfo) -> Unit = { },
    onUpdateUriFailed: (SecurityException) -> Unit = { },
) {
    val context = LocalContext.current
    BoxWithConstraints(modifier) {
        val cw = constraints.maxWidth
        val ch = constraints.maxHeight

        val bitmap by produceState<Bitmap?>(null, selectedImage.uri, cw, ch) {
            value = if (cw > 0 && ch > 0) {
                try {
                    decodeSampledBitmapFromResource(
                        context.contentResolver,
                        selectedImage.uri.toUri(),
                        cw,
                        ch,
                    ).data?.bitmap
                } catch (se: SecurityException) {
                    onUpdateUriFailed(se); null
                }
            } else null
        }

        val bmp = bitmap
        if (bmp != null && cw > 0 && ch > 0) {
            val scale = min(cw.toFloat() / bmp.width, ch.toFloat() / bmp.height)
            val drawW = bmp.width * scale
            val drawH = bmp.height * scale
            val left = (cw - drawW) / 2f
            val top = (ch - drawH) / 2f

            val tileMode = waterMark.obtainTileMode()
            var offsetX by remember(selectedImage.uri) { mutableStateOf(selectedImage.offsetX) }
            var offsetY by remember(selectedImage.uri) { mutableStateOf(selectedImage.offsetY) }

            val imagePaint = remember { Paint(Paint.FILTER_BITMAP_FLAG) }
            val layoutPaint = remember { Paint() }
            val imageMatrix = remember { Matrix() }
            val scope = rememberCoroutineScope()
            val shouldDrawWatermark = waterMark.text.isNotEmpty()
            val useCommon = CommonRasterFlags.useCommonRasterPreview

            val commonComposed by produceState<Bitmap?>(
                null,
                useCommon,
                waterMark,
                bmp,
                offsetX,
                offsetY,
                selectedImage.uri,
            ) {
                value = if (useCommon) {
                    withContext(Dispatchers.Default) {
                        try {
                            val info = selectedImage.copy(offsetX = offsetX, offsetY = offsetY).also {
                                it.width = bmp.width
                                it.height = bmp.height
                            }
                            val icon = if (waterMark.markMode == WatermarkMode.Image) {
                                decodeSampledBitmapFromResource(
                                    context.contentResolver,
                                    waterMark.iconUri.toUri(),
                                    bmp.width,
                                    bmp.height,
                                ).data?.bitmap
                            } else null
                            AndroidCommonRaster.composeToBitmap(context, bmp, waterMark, info, icon)
                        } catch (_: Throwable) {
                            null
                        }
                    }
                } else null
            }

            val cellShader by produceState<WaterMarkShader?>(
                null,
                useCommon,
                waterMark,
                drawW.toInt(),
                drawH.toInt(),
                selectedImage.uri,
            ) {
                value = if (!useCommon) {
                    buildPreviewShader(
                        context,
                        waterMark,
                        selectedImage.uri.toUri(),
                        drawW.toInt(),
                        drawH.toInt(),
                    )
                } else null
            }

            val isClamp = tileMode == Shader.TileMode.CLAMP
            val clampCellSize by produceState<Pair<Float, Float>?>(
                null,
                useCommon,
                isClamp,
                waterMark,
                bmp.width,
                bmp.height,
                drawW,
                drawH,
                selectedImage.uri,
            ) {
                value = if (useCommon && isClamp) {
                    withContext(Dispatchers.Default) {
                        try {
                            val info = selectedImage.copy(offsetX = 0.5f, offsetY = 0.5f).also {
                                it.width = bmp.width
                                it.height = bmp.height
                            }
                            val icon = if (waterMark.markMode == WatermarkMode.Image) {
                                decodeSampledBitmapFromResource(
                                    context.contentResolver,
                                    waterMark.iconUri.toUri(),
                                    bmp.width,
                                    bmp.height,
                                ).data?.bitmap
                            } else null
                            val cellProbe = AndroidCommonRaster.cellSizePx(context, waterMark, info, icon)
                            val sx = drawW / bmp.width.toFloat()
                            val sy = drawH / bmp.height.toFloat()
                            (cellProbe.first * sx) to (cellProbe.second * sy)
                        } catch (_: Throwable) {
                            drawW to drawH
                        }
                    }
                } else null
            }

            val canvasModifier = if (isClamp) {
                val cellW = if (useCommon) {
                    clampCellSize?.first ?: drawW
                } else {
                    cellShader?.width?.toFloat() ?: 0f
                }
                val cellH = if (useCommon) {
                    clampCellSize?.second ?: drawH
                } else {
                    cellShader?.height?.toFloat() ?: 0f
                }
                val hitReady = useCommon || (cellShader != null && cellW > 0f && cellH > 0f)
                Modifier
                    .fillMaxSize()
                    .pointerInput(drawW, drawH, left, top, cellW, cellH, hitReady, useCommon) {
                        if (!hitReady) return@pointerInput
                        var draggingWatermark = false
                        detectDragGestures(
                            onDragStart = { start ->
                                draggingWatermark = isTouchingClampWatermark(
                                    pointer = start,
                                    left = left,
                                    top = top,
                                    regionWidth = drawW,
                                    regionHeight = drawH,
                                    offsetX = offsetX,
                                    offsetY = offsetY,
                                    cellWidth = cellW,
                                    cellHeight = cellH,
                                )
                            },
                            onDragEnd = {
                                if (draggingWatermark) {
                                    if (isClampWatermarkOutOfDrawable(
                                            offsetX, offsetY, drawW, drawH, cellW, cellH,
                                        )
                                    ) {
                                        val startX = offsetX
                                        val startY = offsetY
                                        val centerX = ((drawW - cellW) / 2f) / drawW
                                        val centerY = ((drawH - cellH) / 2f) / drawH
                                        scope.launch {
                                            Animatable(0f).animateTo(
                                                1f,
                                                animationSpec = tween(durationMillis = 300),
                                            ) {
                                                offsetX = startX + (centerX - startX) * value
                                                offsetY = startY + (centerY - startY) * value
                                            }
                                            onOffsetChanged(
                                                selectedImage.copy(offsetX = centerX, offsetY = centerY),
                                            )
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
                                offsetX += drag.x / drawW
                                offsetY += drag.y / drawH
                            }
                        }
                    }
            } else {
                Modifier.fillMaxSize()
            }

            Canvas(modifier = canvasModifier) {
                drawIntoCanvas { canvas ->
                    val nc = canvas.nativeCanvas
                    imageMatrix.apply {
                        reset()
                        postScale(scale, scale)
                        postTranslate(left, top)
                    }
                    if (useCommon) {
                        val composed = commonComposed
                        if (composed != null) {
                            nc.drawBitmap(composed, imageMatrix, imagePaint)
                        } else {
                            nc.drawBitmap(bmp, imageMatrix, imagePaint)
                        }
                    } else {
                        nc.drawBitmap(bmp, imageMatrix, imagePaint)
                        val shader = cellShader
                        if (shouldDrawWatermark && shader != null) {
                            WatermarkRenderer.compose(
                                canvas = nc,
                                shader = shader,
                                tileMode = tileMode,
                                paint = layoutPaint,
                                left = left,
                                top = top,
                                regionWidth = drawW,
                                regionHeight = drawH,
                                offsetX = offsetX,
                                offsetY = offsetY,
                            )
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

private suspend fun buildPreviewShader(
    context: android.content.Context,
    waterMark: WaterMark,
    imageUri: android.net.Uri,
    drawWidth: Int,
    drawHeight: Int,
): WaterMarkShader? {
    val imageInfo = ImageInfo(imageUri.toMediaRef()).apply {
        width = drawWidth
        height = drawHeight
    }
    val bitmapPaint = TextPaint().applyConfig(imageInfo, waterMark, isScale = false)
    return when (waterMark.markMode) {
        WatermarkMode.Text ->
            WatermarkRenderer.buildTextShader(
                imageInfo, waterMark, bitmapPaint, androidTextMeasureEnv(context), Dispatchers.IO,
            )
        WatermarkMode.Image -> {
            val icon = decodeSampledBitmapFromResource(
                context.contentResolver, waterMark.iconUri.toUri(), drawWidth, drawHeight,
            ).data?.bitmap ?: return null
            WatermarkRenderer.buildIconShader(
                imageInfo, icon, waterMark, bitmapPaint, scale = false, Dispatchers.IO,
            )
        }
    }
}
