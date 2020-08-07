package me.rosuh.easywatermark.widget

import android.content.Context
import android.graphics.*
import android.graphics.drawable.Drawable
import android.net.Uri
import android.util.AttributeSet
import android.util.Log
import kotlinx.coroutines.*
import me.rosuh.easywatermark.BuildConfig
import me.rosuh.easywatermark.ktx.applyConfig
import me.rosuh.easywatermark.model.WaterMarkConfig
import me.rosuh.easywatermark.utils.decodeSampledBitmapFromResource
import kotlin.coroutines.CoroutineContext
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sqrt


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
    private val limitBounds = Rect()

    @Volatile
    private var iconBitmap: Bitmap? = null

    private var localIconUri: Uri? = null

    private var exceptionHandler: CoroutineExceptionHandler =
        CoroutineExceptionHandler { _: CoroutineContext, throwable: Throwable ->
            Log.d(
                this::class.simpleName,
                "Throw OOM in WaterMarkImageView ${throwable.message.toString()}"
            )
            generateBitmapJob?.cancel()
        }

    /**
     * Using single thread to making all building bitmap working serially. Avoiding concurrency problem about [Bitmap.recycle].
     * 使用额外的单线程上下文来避免 [buildIconBitmapShader] 方法因并发导致的问题。因为 Bitmap 需要适时回收。
     */
    @ObsoleteCoroutinesApi
    val generateBitmapCoroutineCtx by lazy { newSingleThreadContext("Generate_Bitmap") }

    private var generateBitmapJob: Job? = null

    var config: WaterMarkConfig? = null
        set(value) {
            field = value
            generateBitmapJob = launch(exceptionHandler) {
                setImageURI(value?.uri)
                paint.applyConfig(value)
                val canDraw = field != null
                        && (field!!.canDrawIcon() || field!!.canDrawText())
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
                                iconBitmap = decodeSampledBitmapFromResource(
                                    context.contentResolver,
                                    field!!.iconUri,
                                    limitBounds.width(),
                                    limitBounds.height()
                                )
                                // and flagging the old one should be recycled
                                shouldRecycled = true
                            }

                            layoutPaint.shader = null
                            buildIconBitmapShader(
                                iconBitmap!!,
                                shouldRecycled,
                                limitBounds,
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

    private val paint: Paint by lazy {
        Paint().applyConfig(config)
    }

    private val layoutPaint: Paint by lazy {
        Paint()
    }

    private var bounds: Rect = Rect()

    private var actualDrawableBounds = Rect()

    init {
        if (!config?.uri?.toString().isNullOrEmpty()) {
            setImageURI(config?.uri)
        }
    }

    private var layoutShader: BitmapShader? = null

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        limitBounds.set(0, 0, w, h)
    }

    override fun onDraw(canvas: Canvas?) {
        super.onDraw(canvas)
        if (config?.text.isNullOrEmpty() || config?.uri.toString().isEmpty()) {
            return
        }
        layoutPaint.shader = layoutShader
        if (layoutShader != null) {
            canvas?.drawRect(0f, 0f, measuredWidth.toFloat(), measuredHeight.toFloat(), layoutPaint)
        }
    }

    private fun getBitmapPositionInsideImageView() {
        // Get image dimensions
        // Get image matrix values and place them in an array
        val f = FloatArray(9)
        imageMatrix.getValues(f)

        // Extract the scale values using the constants (if aspect ratio maintained, scaleX == scaleY)
        val scaleX = f[Matrix.MSCALE_X]
        val scaleY = f[Matrix.MSCALE_Y]

        // Get the drawable (could also get the bitmap behind the drawable and getWidth/getHeight)
        val d: Drawable = drawable
        val origW = d.intrinsicWidth
        val origH = d.intrinsicHeight

        // Calculate the actual dimensions
        val actW = (origW * scaleX).roundToInt()
        val actH = (origH * scaleY).roundToInt()

        // Get image position
        // We assume that the image is centered into ImageView
        val imgViewW: Int = measuredWidth
        val imgViewH: Int = measuredHeight
        val top = (imgViewH - actH) / 2
        val left = (imgViewW - actW) / 2
        actualDrawableBounds.set(left, top, actW, actH)
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

            if (BuildConfig.DEBUG) {
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
            if (BuildConfig.DEBUG) {
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
        ): BitmapShader? = withContext(coroutineContext) {
            val textWidth = textBounds.width().toFloat().coerceAtLeast(1f) + 10
            val textHeight = textBounds.height().toFloat().coerceAtLeast(1f) + 10

            val maxSize = sqrt(textHeight.pow(2) + textWidth.pow(2)).toInt()
            val finalWidth = maxSize + config.horizonGapPercent
            val finalHeight = maxSize + config.verticalGapPercent
            val bitmap =
                Bitmap.createBitmap(finalWidth, finalHeight, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            if (BuildConfig.DEBUG) {
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
                (finalWidth - textWidth) / 2,
                (finalHeight + textHeight) / 2,
                textPaint
            )
            if (BuildConfig.DEBUG) {
                canvas.restore()
            }

            return@withContext BitmapShader(
                bitmap!!,
                Shader.TileMode.REPEAT,
                Shader.TileMode.REPEAT
            )
        }
    }
}