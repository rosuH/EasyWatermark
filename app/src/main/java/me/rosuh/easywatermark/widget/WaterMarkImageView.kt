package me.rosuh.easywatermark.widget

import android.content.Context
import android.graphics.*
import android.graphics.drawable.Drawable
import android.net.Uri
import android.util.AttributeSet
import android.util.Log
import androidx.palette.graphics.Palette
import kotlinx.coroutines.*
import me.rosuh.easywatermark.BuildConfig
import me.rosuh.easywatermark.ktx.applyConfig
import me.rosuh.easywatermark.model.WaterMarkConfig
import me.rosuh.easywatermark.utils.ImageHelper
import me.rosuh.easywatermark.utils.TextBitmapCache
import me.rosuh.easywatermark.utils.decodeSampledBitmapFromResource
import kotlin.coroutines.CoroutineContext
import kotlin.math.*


class WaterMarkImageView : androidx.appcompat.widget.AppCompatImageView, CoroutineScope {
    constructor(context: Context) : super(context)
    constructor(context: Context, attrs: AttributeSet?) : super(context, attrs)
    constructor(context: Context, attrs: AttributeSet?, defStyleAttr: Int) : super(
        context,
        attrs,
        defStyleAttr
    )

    override val coroutineContext: CoroutineContext
        get() = Dispatchers.Main

    private val iconBounds = Rect()

    @Volatile
    private var curUri: Uri? = null

    @Volatile
    private var iconBitmap: Bitmap? = null

    private var localIconUri: Uri? = null

    private var onColorReady: (palette: Palette) -> Unit = {}

    var drawableBounds = RectF()
        private set

    fun doOnColorReady(colorReady: (palette: Palette) -> Unit) {
        onColorReady = colorReady
    }

    private var exceptionHandler: CoroutineExceptionHandler =
        CoroutineExceptionHandler { _: CoroutineContext, throwable: Throwable ->
            Log.e(
                this::class.simpleName,
                "Throw Exception in WaterMarkImageView ${throwable.message.toString()}"
            )
            throwable.printStackTrace()
            generateBitmapJob?.cancel()
        }

    /**
     * Using single thread to making all building bitmap working serially. Avoiding concurrency problem about [Bitmap.recycle].
     * 使用额外的单线程上下文来避免 [buildIconBitmapShader] 方法因并发导致的问题。因为 Bitmap 需要适时回收。
     */
    @ObsoleteCoroutinesApi
    val generateBitmapCoroutineCtx by lazy { newSingleThreadContext("Generate_Bitmap") }

    private var generateBitmapJob: Job? = null

    @ObsoleteCoroutinesApi
    var config: WaterMarkConfig? = null
        set(value) {
            field = value
            generateBitmapJob = launch(exceptionHandler) {
                if (curUri != field?.uri) {
                    val scale = floatArrayOf(1f, 1f)
                    val imageBitmapRect = decodeSampledBitmapFromResource(
                        context.contentResolver,
                        config!!.uri,
                        this@WaterMarkImageView.measuredWidth - paddingStart * 2,
                        this@WaterMarkImageView.measuredHeight - paddingTop * 2,
                        scale
                    )
                    if (imageBitmapRect.isFailure() || imageBitmapRect.data == null) {
                        return@launch
                    }
                    val imageBitmap = imageBitmapRect.data
                    setImageBitmap(imageBitmap)
                    drawableBounds = generateDrawableBounds()
                    scale[0] = scale[0] * imageBitmap!!.width.toFloat() / drawableBounds.width()
                    scale[1] = scale[1] * imageBitmap.height.toFloat() / drawableBounds.height()
                    field?.imageScale = scale
                    imageBitmap.let { Palette.Builder(it).generate() }.let {
                        onColorReady.invoke(it)
                    }
                    curUri = field?.uri
                }

                paint.applyConfig(value)
                val canDraw = field?.canDraw() ?: return@launch
                if (canDraw) {
                    layoutShader = when (field!!.markMode) {
                        WaterMarkConfig.MarkMode.Text -> {
                            paint.getTextBounds(config!!.text, 0, config!!.text.length, bounds)
                            buildTextBitmapShader(
                                field!!,
                                bounds,
                                paint,
                                generateBitmapCoroutineCtx
                            )
                        }
                        WaterMarkConfig.MarkMode.Image -> {
                            var shouldRecycled = false
                            if (iconBitmap == null || localIconUri != field!!.iconUri) {
                                // if uri was changed, create a new bitmap
                                // Here would decode a inSampled bitmap, the max size was imageView's width and height
                                val iconBitmapRect = decodeSampledBitmapFromResource(
                                    context.contentResolver,
                                    field!!.iconUri,
                                    iconBounds.width(),
                                    iconBounds.height()
                                )
                                if (iconBitmapRect.isFailure()) {
                                    return@launch
                                }
                                iconBitmap = iconBitmapRect.data
                                // and flagging the old one should be recycled
                                shouldRecycled = true
                            }

                            layoutPaint.shader = null
                            buildIconBitmapShader(
                                iconBitmap!!,
                                shouldRecycled,
                                iconBounds,
                                field!!,
                                paint,
                                generateBitmapCoroutineCtx
                            )
                        }
                    }
                    invalidate()
                }
            }
        }

    @ObsoleteCoroutinesApi
    private val paint: Paint by lazy {
        Paint().applyConfig(config)
    }

    private val layoutPaint: Paint by lazy {
        Paint()
    }

    private var bounds: Rect = Rect()

    private var layoutShader: BitmapShader? = null

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        iconBounds.set(0, 0, w, h)
    }

    @ObsoleteCoroutinesApi
    override fun onDraw(canvas: Canvas?) {
        super.onDraw(canvas)
        if (config?.text.isNullOrEmpty() || config?.uri.toString().isEmpty()) {
            return
        }
        layoutPaint.shader = layoutShader
        if (layoutShader != null) {
            ImageHelper.draw(canvas, layoutPaint, drawableBounds)
        }
    }

    private fun generateDrawableBounds(): RectF {
        val bounds = RectF()
        val drawable: Drawable = drawable
        imageMatrix.mapRect(bounds, RectF(drawable.bounds))
        bounds.set(
            bounds.left + paddingLeft,
            bounds.top + paddingTop,
            bounds.right + paddingRight,
            bounds.bottom + paddingBottom,
        )
        return bounds
    }

    companion object {

        private fun calculateScaleRatio(textSize: Float): Float {
            return (textSize / 50).coerceAtLeast(0.1f)
        }

        private fun calculateFinalWidth(config: WaterMarkConfig, maxSize: Int): Int {
            return (maxSize * ((config.horizonGapPercent / 100f) + 1)).toInt()
        }

        private fun calculateFinalHeight(config: WaterMarkConfig, maxSize: Int): Int {
            return (maxSize * ((config.verticalGapPercent / 100f) + 1)).toInt()
        }

        private fun calculateMaxSize(w: Float, h: Float): Int {
            return sqrt(w.pow(2) + h.pow(2)).toInt()
        }

        suspend fun buildIconBitmapShader(
            srcBitmap: Bitmap,
            shouldRecycled: Boolean,
            limitBounds: Rect,
            config: WaterMarkConfig,
            textPaint: Paint,
            coroutineContext: CoroutineContext
        ): BitmapShader? = withContext(coroutineContext) {
            if (srcBitmap.isRecycled) {
                return@withContext null
            }
            val showDebugRect = BuildConfig.DEBUG && false
            val rawWidth = srcBitmap.width.toFloat().coerceAtLeast(1f)
                .coerceAtMost(limitBounds.width().toFloat())
            val rawHeight = srcBitmap.height.toFloat().coerceAtLeast(1f)
                .coerceAtMost(limitBounds.height().toFloat())

            val maxSize = calculateMaxSize(rawHeight, rawWidth)

            val finalWidth = calculateFinalWidth(config, maxSize)
            val finalHeight = calculateFinalHeight(config, maxSize)
            // textSize represents scale ratio of icon.
            val scaleRatio = calculateScaleRatio(textPaint.textSize)

            val targetBitmap = Bitmap.createBitmap(
                (finalWidth * scaleRatio).toInt(),
                (finalHeight * scaleRatio).toInt(),
                Bitmap.Config.ARGB_8888
            )

            val canvas = Canvas(targetBitmap)

            val scaleBitmap = Bitmap.createScaledBitmap(
                srcBitmap,
                (rawWidth * scaleRatio).toInt(), (rawHeight * scaleRatio).toInt(),
                false
            )!!

            if (shouldRecycled && !srcBitmap.isRecycled && scaleBitmap != srcBitmap) {
                // scaleBitmap may equals with srcBitmap when scale was 1.0, so we should check that
                srcBitmap.recycle()
            }

            if (showDebugRect) {
                val tmpPaint = Paint().apply {
                    color = Color.RED
                    strokeWidth = 1f
                    style = Paint.Style.STROKE
                }
                canvas.drawRect(0f, 0f, finalWidth * scaleRatio, finalHeight * scaleRatio, tmpPaint)
                canvas.save()
            }
            canvas.rotate(
                config.degree,
                (finalWidth * scaleRatio / 2),
                (finalHeight * scaleRatio / 2)
            )

            canvas.drawBitmap(
                scaleBitmap,
                (finalWidth * scaleRatio - scaleBitmap.width) / 2.toFloat(),
                (finalHeight * scaleRatio - scaleBitmap.height) / 2.toFloat(),
                textPaint
            )
            if (showDebugRect) {
                canvas.restore()
            }
            return@withContext BitmapShader(
                targetBitmap,
                Shader.TileMode.REPEAT,
                Shader.TileMode.REPEAT
            )
        }

        suspend fun buildTextBitmapShader(
            config: WaterMarkConfig,
            textBounds: Rect,
            textPaint: Paint,
            coroutineContext: CoroutineContext
        ): BitmapShader = withContext(coroutineContext) {
            val showDebugRect = BuildConfig.DEBUG && false
            val textWidth = textBounds.width().toFloat().coerceAtLeast(1f)
            val textHeight = textBounds.height().toFloat().coerceAtLeast(1f)

            val radians = Math.toRadians(
                when (config.degree) {
                    in 0.0..90.0 -> config.degree.toDouble()
                    in 90.0..270.0 -> {
                        abs(180 - config.degree.toDouble())
                    }
                    else -> 360 - config.degree.toDouble()
                }
            )
            val fixWidth = textWidth * cos(radians) + textHeight * sin(radians)
            val fixHeight = textWidth * sin(radians) + textHeight * cos(radians)

            val finalWidth = calculateFinalWidth(config, fixWidth.toInt())
            val finalHeight = calculateFinalHeight(config, fixHeight.toInt())
            val bitmap = TextBitmapCache.get(finalWidth, finalHeight)
            val canvas = Canvas(bitmap)
            if (showDebugRect) {
                val tmpPaint = Paint().apply {
                    color = Color.RED
                    strokeWidth = 1f
                    style = Paint.Style.STROKE
                }
                canvas.drawRect(0f, 0f, finalWidth.toFloat(), finalHeight.toFloat(), tmpPaint)
                canvas.save()
            }
            canvas.rotate(
                config.degree,
                (finalWidth / 2).toFloat(),
                (finalHeight / 2).toFloat()
            )

            canvas.drawText(
                config.text,
                finalWidth.toFloat() / 2,
                (finalHeight + textHeight) / 2,
                textPaint
            )
            if (showDebugRect) {
                canvas.restore()
            }

            return@withContext BitmapShader(
                bitmap,
                Shader.TileMode.REPEAT,
                Shader.TileMode.REPEAT
            )
        }
    }
}