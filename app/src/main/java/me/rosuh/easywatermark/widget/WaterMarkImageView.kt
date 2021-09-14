package me.rosuh.easywatermark.widget

import android.animation.ObjectAnimator
import android.content.Context
import android.graphics.*
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.Build
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.util.AttributeSet
import android.util.Log
import androidx.core.animation.addListener
import androidx.core.animation.doOnEnd
import androidx.core.animation.doOnStart
import androidx.core.content.ContextCompat
import androidx.core.graphics.withSave
import androidx.palette.graphics.Palette
import kotlinx.coroutines.*
import me.rosuh.easywatermark.BuildConfig
import me.rosuh.easywatermark.R
import me.rosuh.easywatermark.model.WaterMarkConfig
import me.rosuh.easywatermark.utils.bitmap.decodeSampledBitmapFromResource
import me.rosuh.easywatermark.utils.ktx.applyConfig
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
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

    private var isAnimating = AtomicBoolean(false)

    private var localIconUri: Uri? = null

    var drawableBounds = RectF()
        private set

    private var onBgReady: (palette: Palette) -> Unit = {}

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
    private val generateBitmapCoroutineCtx by lazy {
        Executors.newSingleThreadExecutor().asCoroutineDispatcher()
    }

    private var generateBitmapJob: Job? = null

    var config: WaterMarkConfig? = null
        set(value) {
            field = value
            field?.let { applyNewConfig(it) }
        }

    private val drawableAlphaAnimator by lazy {
        ObjectAnimator.ofInt(0, 255).apply {
            addUpdateListener {
                val alpha = it.animatedValue as Int
                this@WaterMarkImageView.drawable?.alpha = alpha
            }
            addListener {
                doOnStart {
                    isAnimating.set(true)
                }
                doOnEnd {
                    isAnimating.set(false)
                    invalidate()
                }
            }
            duration = 250
        }
    }

    private fun applyNewConfig(newConfig: WaterMarkConfig) {
        generateBitmapJob = launch(exceptionHandler) {
            // quick check is the same image
            if (curUri != newConfig.uri) {
                // hide iv
                this@WaterMarkImageView.drawable?.alpha = 0
                // decode with inSample
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
                // setting background color via Palette
                applyBg(imageBitmap)
                // setting the bitmap of image
                setImageBitmap(imageBitmap)
                // animate to show
                drawableAlphaAnimator.start()
                // collect the drawable of new image in ImageView
                drawableBounds = generateDrawableBounds()
                scale[0] = scale[0] * imageBitmap!!.width.toFloat() / drawableBounds.width()
                scale[1] = scale[1] * imageBitmap.height.toFloat() / drawableBounds.height()
                // the scale factor which of real image and render bitmap
                newConfig.imageScale = scale
                curUri = newConfig.uri
            }
            // apply new config to paint
            textPaint.applyConfig(newConfig)
            layoutShader = when (newConfig.markMode) {
                WaterMarkConfig.MarkMode.Text -> {
                    buildTextBitmapShader(
                        newConfig,
                        textPaint,
                        generateBitmapCoroutineCtx
                    )
                }
                WaterMarkConfig.MarkMode.Image -> {
                    var shouldRecycled = false
                    if (iconBitmap == null || localIconUri != newConfig.iconUri) {
                        // if uri was changed, create a new bitmap
                        // Here would decode a inSampled bitmap, the max size was imageView's width and height
                        val iconBitmapRect = decodeSampledBitmapFromResource(
                            context.contentResolver,
                            newConfig.iconUri,
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
                        newConfig,
                        textPaint,
                        generateBitmapCoroutineCtx
                    )
                }
            }
            invalidate()
        }
    }

    private fun applyBg(imageBitmap: Bitmap?) {
        imageBitmap?.let { Palette.Builder(it).generate() }?.let { palette ->
            val color = palette.darkMutedSwatch?.rgb ?: ContextCompat.getColor(
                context,
                R.color.colorSecondary
            )
            setBackgroundColor(color)
            this.onBgReady.invoke(palette)
        }
    }

    private val textPaint: TextPaint by lazy {
        TextPaint().applyConfig(config)
    }

    private val layoutPaint: Paint by lazy {
        Paint()
    }

    private var layoutShader: BitmapShader? = null

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        iconBounds.set(0, 0, w, h)
    }

    override fun onDraw(canvas: Canvas?) {
        super.onDraw(canvas)
        if (config?.text.isNullOrEmpty() || config?.uri.toString().isEmpty()
            || layoutShader == null
        ) {
            return
        }
        layoutPaint.shader = layoutShader
        canvas?.withSave {
            translate(drawableBounds.left, drawableBounds.top)
            drawRect(
                0f,
                0f,
                drawableBounds.right - drawableBounds.left,
                drawableBounds.bottom - drawableBounds.top,
                layoutPaint
            )
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

    fun onBgReady(block: (palette: Palette) -> Unit) {
        this.onBgReady = block
    }

    fun reset() {
        config = null
        curUri = null
        setImageBitmap(null)
        setBackgroundColor(Color.TRANSPARENT)
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

        /**
         * Generate bitmap shader from input text.
         * Text watermark implemented by bitmap shader.
         * Using [StaticLayout] to draw multi line text.
         * @author hi@rosuh.me
         */
        suspend fun buildTextBitmapShader(
            config: WaterMarkConfig,
            textPaint: TextPaint,
            coroutineContext: CoroutineContext
        ): BitmapShader? = withContext(coroutineContext) {
            if (config.text.isBlank()) {
                return@withContext null
            }
            val showDebugRect = BuildConfig.DEBUG && false
            var maxLineWidth = 0
            // calculate the max width of all lines
            config.text.split("\n").forEach {
                val startIndex = config.text.indexOf(it).coerceAtLeast(0)
                val lineWidth = textPaint.measureText(
                    config.text,
                    startIndex,
                    (startIndex + it.length).coerceAtMost(config.text.length)
                ).toInt()
                maxLineWidth = max(maxLineWidth, lineWidth)
            }

            val staticLayout = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                StaticLayout.Builder.obtain(
                    config.text,
                    0,
                    config.text.length,
                    textPaint,
                    maxLineWidth
                )
                    .setAlignment(Layout.Alignment.ALIGN_NORMAL)
                    .build()
            } else {
                StaticLayout(
                    config.text,
                    textPaint,
                    maxLineWidth,
                    Layout.Alignment.ALIGN_NORMAL,
                    1.0f,
                    0f,
                    false
                )
            }

            val textWidth = staticLayout.width.toFloat().coerceAtLeast(1f)
            val textHeight = staticLayout.height.toFloat().coerceAtLeast(1f)

            val radians = Math.toRadians(
                when (config.degree) {
                    in 0.0..90.0 -> config.degree.toDouble()
                    in 90.0..270.0 -> {
                        abs(180 - config.degree.toDouble())
                    }
                    else -> 360 - config.degree.toDouble()
                }
            )
            // Generate tmp size from rotation degree, all degree have it's own size.
            val fixWidth = textWidth * cos(radians) + textHeight * sin(radians)
            val fixHeight = textWidth * sin(radians) + textHeight * cos(radians)

            val finalWidth = calculateFinalWidth(config, fixWidth.toInt())
            val finalHeight = calculateFinalHeight(config, fixHeight.toInt())
            val bitmap = Bitmap.createBitmap(finalWidth, finalHeight, Bitmap.Config.ARGB_8888)
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
            // rotate by user input
            canvas.rotate(
                config.degree,
                (finalWidth / 2).toFloat(),
                (finalHeight / 2).toFloat()
            )
            // draw text
            canvas.withSave {
                this.translate(
                    ((finalWidth) / 2).toFloat(),
                    ((finalHeight - staticLayout.getLineBottom(0) - staticLayout.getLineTop(0)) / 2).toFloat()
                )
                staticLayout.draw(canvas)
            }

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