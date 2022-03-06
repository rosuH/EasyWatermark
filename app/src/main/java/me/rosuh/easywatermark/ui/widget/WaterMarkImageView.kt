package me.rosuh.easywatermark.ui.widget

import android.animation.ObjectAnimator
import android.content.Context
import android.graphics.*
import android.graphics.Matrix.MSCALE_X
import android.graphics.Matrix.MSKEW_X
import android.graphics.drawable.ColorDrawable
import android.net.Uri
import android.os.Build
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.util.AttributeSet
import android.util.Log
import android.widget.ImageView
import androidx.core.graphics.withRotation
import androidx.core.graphics.withSave
import androidx.dynamicanimation.animation.FloatPropertyCompat
import androidx.dynamicanimation.animation.SpringAnimation
import androidx.dynamicanimation.animation.SpringForce.DAMPING_RATIO_LOW_BOUNCY
import androidx.palette.graphics.Palette
import kotlinx.coroutines.*
import me.rosuh.easywatermark.data.model.ImageInfo
import me.rosuh.easywatermark.data.model.ViewInfo
import me.rosuh.easywatermark.data.model.WaterMark
import me.rosuh.easywatermark.data.repo.WaterMarkRepository
import me.rosuh.easywatermark.utils.bitmap.decodeSampledBitmapFromResource
import me.rosuh.easywatermark.utils.ktx.*
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

    @Volatile
    private var curImageInfo: ImageInfo = ImageInfo(Uri.EMPTY)

    private var decodedUri: Uri = Uri.EMPTY

    @Volatile
    private var localIconUri: Uri = Uri.EMPTY

    @Volatile
    private var iconBitmap: Bitmap? = null

    private var enableWaterMark = AtomicBoolean(false)

    private val drawableBounds = RectF()

    private var onBgReady: (palette: Palette) -> Unit = {}

    private var exceptionHandler: CoroutineExceptionHandler =
        CoroutineExceptionHandler { _: CoroutineContext, throwable: Throwable ->
            Log.e(
                this::class.simpleName,
                "Throw Exception in WaterMarkImageView ${throwable.message}"
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

    fun updateUri(init: Boolean, imageInfo: ImageInfo) {
        config?.let {
            applyNewConfig(init, it, imageInfo)
        } ?: kotlin.run {
            curImageInfo = imageInfo
        }
    }

    var config: WaterMark? = null
        set(value) {
            if (field == value) {
                return
            }
            field = value
            if (curImageInfo.uri.toString().isBlank()) return
            field?.let { applyNewConfig(false, it, curImageInfo) }
        }

    private val drawableAlphaAnimator by lazy {
        ObjectAnimator.ofInt(0, 255).apply {
            addUpdateListener {
                val alpha = it.animatedValue as Int
                this@WaterMarkImageView.drawable?.alpha = alpha
            }
            duration = ANIMATION_DURATION
        }
    }

    private fun applyNewConfig(
        isInit: Boolean,
        newConfig: WaterMark,
        imageInfo: ImageInfo
    ) {
        val uri = imageInfo.uri
        generateBitmapJob?.cancel()
        generateBitmapJob = launch(exceptionHandler) {
            // quick check is the same image
            if (decodedUri != uri) {
                // hide iv
                this@WaterMarkImageView.drawable?.alpha = 0
                drawableAlphaAnimator.cancel()
                // decode with inSample
                val decodeResult = decodeSampledBitmapFromResource(
                    context.contentResolver,
                    uri,
                    calculateDrawLimitWidth(
                        this@WaterMarkImageView.measuredWidth,
                        this@WaterMarkImageView.paddingStart
                    ),
                    calculateDrawLimitHeight(
                        this@WaterMarkImageView.measuredHeight,
                        this@WaterMarkImageView.paddingTop
                    )
                )
                val bitmapValue = decodeResult.data
                if (decodeResult.isFailure() || bitmapValue == null) {
                    return@launch
                }
                // setting the bitmap of image
                val imageBitmap = bitmapValue.bitmap
                // adjust bitmap via matrix
                setImageBitmap(imageBitmap)
                val matrix = adjustMatrix(
                    imageMatrix,
                    measuredWidth,
                    measuredHeight,
                    paddingLeft,
                    paddingTop,
                    imageBitmap.width,
                    imageBitmap.height
                )
                imageMatrix = matrix
                // setting background color via Palette
                applyBg(imageBitmap)
                // animate to show
                // when showing first bitmap we need to wait the imageview prepared.
                if (isInit) {
                    enableWaterMark.set(false)
                }
                drawableAlphaAnimator.start()
                if (isInit) {
                    invalidate()
                    delay(drawableAlphaAnimator.duration - 30)
                    enableWaterMark.set(true)
                }
                // collect the drawable of new image in ImageView
                generateDrawableBounds()
                // the scale factor which of real image and render bitmap
                imageInfo.inSample = bitmapValue.inSample
                curImageInfo = imageInfo
                curImageInfo.width = drawableBounds.width().toInt()
                curImageInfo.height = drawableBounds.height().toInt()
                decodedUri = uri
            }
            // apply new config to paint
            textPaint.applyConfig(curImageInfo, newConfig)
            layoutShader = when (newConfig.markMode) {
                WaterMarkRepository.MarkMode.Text -> {
                    buildTextBitmapShader(
                        curImageInfo,
                        newConfig,
                        textPaint,
                        generateBitmapCoroutineCtx
                    )
                }
                WaterMarkRepository.MarkMode.Image -> {
                    if (iconBitmap == null
                        || localIconUri != newConfig.iconUri
                        || (iconBitmap!!.width != newConfig.textSize.toInt() && iconBitmap!!.height != newConfig.textSize.toInt())
                    ) {
                        // if uri was changed, create a new bitmap
                        // Here would decode a inSampled bitmap, the max size was imageView's width and height
                        val iconBitmapRect = decodeSampledBitmapFromResource(
                            context.contentResolver,
                            newConfig.iconUri,
                            measuredWidth,
                            measuredHeight,
                        )
                        if (iconBitmapRect.isFailure() || iconBitmapRect.data == null) {
                            return@launch
                        }
                        iconBitmap = iconBitmapRect.data!!.bitmap
                        // and flagging the old one should be recycled
                    }
                    localIconUri = newConfig.iconUri
                    layoutPaint.shader = null
                    buildIconBitmapShader(
                        curImageInfo,
                        iconBitmap!!,
                        newConfig,
                        textPaint,
                        scale = false,
                        generateBitmapCoroutineCtx
                    )
                }
            }
            postInvalidate()
        }
    }

    private fun getAvailableCanvasWidth() = measuredWidth - paddingLeft - paddingRight
    private fun getAvailableCanvasHeight() = measuredHeight - paddingTop - paddingBottom

    private var bgTransformAnimator: ObjectAnimator? = null

    private fun applyBg(imageBitmap: Bitmap?) {
        launch {
            generatePalette(imageBitmap)?.let { palette ->
                bgTransformAnimator?.cancel()
                val color = palette.bgColor(context)
                bgTransformAnimator =
                    ((background as? ColorDrawable)?.color ?: Color.BLACK).toColor(color) {
                        setBackgroundColor(it.animatedValue as Int)
                    }
                this@WaterMarkImageView.onBgReady.invoke(palette)
            }
        }
    }

    private suspend fun generatePalette(imageBitmap: Bitmap?): Palette? =
        withContext(Dispatchers.Default) {
            return@withContext imageBitmap?.let { Palette.Builder(it).generate() }
        }

    private val textPaint: TextPaint by lazy {
        TextPaint().applyConfig(curImageInfo, config)
    }

    private val layoutPaint: Paint by lazy {
        Paint()
    }

    private var layoutShader: BitmapShader? = null

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        Log.i("onSizeChanged", "$w, $h, $oldh, $oldh")
    }

    override fun onDraw(canvas: Canvas?) {
        super.onDraw(canvas)
        if (config?.text.isNullOrEmpty() || decodedUri.toString().isEmpty() ||
            layoutShader == null
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

    private fun generateDrawableBounds() {
        val bounds = drawableBounds
        imageMatrix.mapRect(bounds, RectF(drawable.bounds))
        bounds.set(
            bounds.left + paddingLeft,
            bounds.top + paddingTop,
            bounds.right + paddingRight,
            bounds.bottom + paddingBottom,
        )
    }

    fun onBgReady(block: (palette: Palette) -> Unit) {
        this.onBgReady = block
    }

    fun reset() {
        curImageInfo = ImageInfo(Uri.EMPTY)
        localIconUri = Uri.EMPTY
        setImageBitmap(null)
        setBackgroundColor(Color.TRANSPARENT)
        decodedUri = Uri.EMPTY
    }

    companion object {

        private const val TAG = "WatermarkImageView"

        const val ANIMATION_DURATION = 450L

        /**
         * Very simple way to fit the image into the canvas
         */
        fun adjustMatrix(
            srcMatrix: Matrix,
            viewWidth: Int,
            viewHeight: Int,
            paddingLeft: Int,
            paddingTop: Int,
            bitmapWidth: Int,
            bitmapHeight: Int
        ): Matrix {
            Log.i(
                TAG,
                "width = $viewWidth, height = $viewHeight, bitmapWidth = $bitmapWidth, bitmapHeight = $bitmapHeight"
            )
            val matrix = Matrix(srcMatrix)
            matrix.reset()
            val canvasWidth = calculateDrawLimitWidth(viewWidth, paddingLeft).toFloat()
            val canvasHeight = calculateDrawLimitHeight(viewHeight, paddingTop).toFloat()
            val scaleX = canvasWidth / bitmapWidth
            val scaleY = canvasHeight / bitmapHeight
            val scale = min(scaleX, scaleY)
            matrix.postScale(scale, scale)
            matrix.postTranslate(
                (canvasWidth - bitmapWidth * scale) / 2,
                (canvasHeight - bitmapHeight * scale) / 2
            )
            return matrix
        }

        private fun adjustHorizonalGap(config: WaterMark, maxSize: Int): Int {
            return (maxSize * ((config.hGap / 100f) + 1)).toInt()
        }

        private fun adjustVerticalGap(config: WaterMark, maxSize: Int): Int {
            return (maxSize * ((config.vGap / 100f) + 1)).toInt()
        }

        private fun calculateMaxSize(w: Float, h: Float): Int {
            return sqrt(w.pow(2) + h.pow(2)).toInt()
        }

        fun calculateDrawLimitWidth(w: Int, ps: Int) = (w - ps * 2)

        fun calculateDrawLimitHeight(h: Int, pt: Int) = (h - pt * 2)

        suspend fun buildIconBitmapShader(
            imageInfo: ImageInfo,
            srcBitmap: Bitmap,
            config: WaterMark,
            textPaint: Paint,
            scale: Boolean,
            coroutineContext: CoroutineContext
        ): BitmapShader? = withContext(coroutineContext) {
            if (srcBitmap.isRecycled) {
                return@withContext null
            }
            val showDebugRect = config.enableBounds
            val rawWidth =
                srcBitmap.width.toFloat().coerceAtLeast(1f).coerceAtMost(imageInfo.width.toFloat())
            val rawHeight = srcBitmap.height.toFloat().coerceAtLeast(1f)
                .coerceAtMost(imageInfo.height.toFloat())

            val maxSize = calculateMaxSize(rawHeight, rawWidth)


            val finalWidth = adjustHorizonalGap(config, maxSize)
            val finalHeight = adjustVerticalGap(config, maxSize)
            // textSize represents scale ratio of icon.
            val scaleRatio = if (scale) {
                imageInfo.scaleX
            } else {
                1f
            } * config.textSize / 14f

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
            imageInfo: ImageInfo,
            config: WaterMark,
            textPaint: TextPaint,
            coroutineContext: CoroutineContext
        ): BitmapShader? = withContext(coroutineContext) {
            if (config.text.isBlank()) {
                return@withContext null
            }
            val showDebugRect = config.enableBounds
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
                .coerceAtMost(imageInfo.width.toFloat())
            val textHeight = staticLayout.height.toFloat().coerceAtLeast(1f)
                .coerceAtMost(imageInfo.height.toFloat())

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

            val finalWidth = adjustHorizonalGap(config, fixWidth.toInt())
            val finalHeight = adjustVerticalGap(config, fixHeight.toInt())
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
