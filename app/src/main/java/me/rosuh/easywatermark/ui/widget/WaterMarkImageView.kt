package me.rosuh.easywatermark.ui.widget

import android.animation.Animator
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.net.Uri
import android.text.TextPaint
import android.util.AttributeSet
import android.util.Log
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import androidx.core.animation.doOnEnd
import androidx.palette.graphics.Palette
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.rosuh.easywatermark.data.model.ImageInfo
import me.rosuh.easywatermark.data.model.ViewInfo
import me.rosuh.easywatermark.data.model.WaterMark
import me.rosuh.easywatermark.render.WatermarkRenderer
import me.rosuh.easywatermark.data.repo.WaterMarkRepository
import me.rosuh.easywatermark.data.repo.WaterMarkRepository.Companion.DEFAULT_TEXT_SIZE
import me.rosuh.easywatermark.data.repo.WaterMarkRepository.Companion.MAX_TEXT_SIZE
import me.rosuh.easywatermark.data.repo.WaterMarkRepository.Companion.MIN_TEXT_SIZE
import me.rosuh.easywatermark.ui.widget.utils.WaterMarkShader
import me.rosuh.easywatermark.utils.bitmap.decodeSampledBitmapFromResource
import me.rosuh.easywatermark.utils.ktx.applyConfig
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.CoroutineContext
import kotlin.math.abs
import kotlin.math.absoluteValue
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
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

    @Volatile
    private var curImageInfo: ImageInfo = ImageInfo(Uri.EMPTY)

    private var decodedUri: Uri = Uri.EMPTY

    @Volatile
    private var localIconUri: Uri = Uri.EMPTY

    @Volatile
    private var iconBitmap: Bitmap? = null

    private var enableWaterMark = AtomicBoolean(false)

    val drawableBounds = RectF()

    private var onBgReady: (palette: Palette) -> Unit = {}

    private var onOffsetChanged: (info: ImageInfo) -> Unit = { _ -> }

    private var onScaleEnd: (textSize: Float) -> Unit = { _ -> }

    private var onViewInfoChanged: (vi: ViewInfo) -> Unit = { _ -> }

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
        imageInfo: ImageInfo,
    ) {
        val uri = imageInfo.uri
//        if (newConfig == config && uri == decodedUri && imageInfo == curImageInfo && isInit.not()) {
//            return
//        }
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
            curImageInfo = imageInfo
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
            onViewInfoChanged(ViewInfo.from(this@WaterMarkImageView))
            postInvalidate()
        }
    }

    private fun applyBg(imageBitmap: Bitmap?) {
        launch {
            generatePalette(imageBitmap)?.let { palette ->
                setBackgroundColor(Color.TRANSPARENT)
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

    private var layoutShader: WaterMarkShader? = null

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        Log.i("onSizeChanged", "$w, $h, $oldh, $oldh")
        if (drawable == null) {
            return
        }
        onViewInfoChanged(ViewInfo.from(this@WaterMarkImageView))
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (config?.text.isNullOrEmpty()
            || decodedUri.toString().isEmpty()
            || layoutShader == null
            || drawableAlphaAnimator.isRunning
        ) {
            return
        }
        // S2a: composition delegated to the shared renderer seam (same helper as export).
        WatermarkRenderer.compose(
            canvas = canvas,
            shader = layoutShader,
            tileMode = config?.obtainTileMode() ?: Shader.TileMode.REPEAT,
            paint = layoutPaint,
            left = drawableBounds.left,
            top = drawableBounds.top,
            regionWidth = drawableBounds.width(),
            regionHeight = drawableBounds.height(),
            offsetX = curImageInfo.offsetX,
            offsetY = curImageInfo.offsetY,
        )
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

    fun onOffsetChanged(block: (info: ImageInfo) -> Unit) {
        this.onOffsetChanged = block
    }

    fun onScaleEnd(block: (textSize: Float) -> Unit) {
        this.onScaleEnd = block
    }

    fun onViewInfoChanged(block: (vi: ViewInfo) -> Unit) {
        this.onViewInfoChanged = block
    }

    fun reset() {
        curImageInfo = ImageInfo(Uri.EMPTY)
        localIconUri = Uri.EMPTY
        setImageBitmap(null)
        setBackgroundColor(Color.TRANSPARENT)
        decodedUri = Uri.EMPTY
    }

    private fun updateWaterMarkOffset(deltaX: Float, deltaY: Float): ImageInfo {
        if (config?.obtainTileMode() != Shader.TileMode.CLAMP) {
            return curImageInfo
        }
        val newOffsetX = (curImageInfo.offsetX + deltaX / drawableBounds.width())
        val newOffsetY = (curImageInfo.offsetY + deltaY / drawableBounds.height())
        curImageInfo = curImageInfo.copy(
            offsetX = newOffsetX,
            offsetY = newOffsetY
        )
        invalidate()
        return curImageInfo
    }

    private var touchRect = RectF()

    fun isOutOfDrawable(deltaX: Float, deltaY: Float): Boolean {
        if (config?.obtainTileMode() != Shader.TileMode.CLAMP) {
            return false
        }
        val newOffsetX = (curImageInfo.offsetX + deltaX / drawableBounds.width())
        val newOffsetY = (curImageInfo.offsetY + deltaY / drawableBounds.height())
        val newX = drawableBounds.left + newOffsetX * drawableBounds.width()
        val newY = drawableBounds.top + newOffsetY * drawableBounds.height()
        val bitmapWith = layoutShader?.width ?: 0
        val bitmapHeight = layoutShader?.height ?: 0
        touchRect.set(
            newX,
            newY,
            newX + bitmapWith,
            newY + bitmapHeight
        )
        Log.i(TAG, "isOutOfDrawable $touchRect, drawableBounds: $drawableBounds")
        return touchRect.right < drawableBounds.left
                || touchRect.left > drawableBounds.right
                || touchRect.top > drawableBounds.bottom
                || touchRect.bottom < drawableBounds.top
    }

    fun isTouchWaterMark(event: MotionEvent): Boolean {
        if (config?.obtainTileMode() != Shader.TileMode.CLAMP) {
            return false
        }
        val shader = layoutShader ?: return false
        val bounds = drawableBounds
        val waterMarkX = bounds.left + curImageInfo.offsetX * bounds.width()
        val waterMarkY = bounds.top + curImageInfo.offsetY * bounds.height()
        return event.x > waterMarkX
                && event.x < waterMarkX + shader.width
                && event.y > waterMarkY
                && event.y < waterMarkY + shader.height
    }

    private var animator: Animator? = null

    fun backToCenter(
        post: (info: ImageInfo, x: Float, y: Float) -> Unit,
    ) {
        animator?.cancel()
        val curOffsetX = curImageInfo.offsetX
        val curOffsetY = curImageInfo.offsetY
        val centerOffsetX = ((drawableBounds.width() - (layoutShader?.width ?: 0)) / 2)
        val centerOffsetY = ((drawableBounds.height() - (layoutShader?.height ?: 0)) / 2)
        val centerOffsetXP = (centerOffsetX) / drawableBounds.width()
        val centerOffsetYP = (centerOffsetY) / drawableBounds.height()
        val info = curImageInfo.copy(
            offsetX = centerOffsetXP,
            offsetY = centerOffsetYP
        )
        curImageInfo = info
        animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 300
            addUpdateListener {
                val value = it.animatedValue as Float
                val offsetX = curOffsetX + (centerOffsetXP - curOffsetX) * value
                val offsetY = curOffsetY + (centerOffsetYP - curOffsetY) * value
                curImageInfo = curImageInfo.copy(
                    offsetX = offsetX,
                    offsetY = offsetY
                )
                invalidate()
            }
            doOnEnd {
                post(info, centerOffsetX, centerOffsetY)
            }
            start()
        }
    }

    private var mScaleFactor = 1f

    private val mScaleDetector by lazy {
        ScaleGestureDetector(context, scaleListener)
    }

    private val scaleListener = object : ScaleGestureDetector.SimpleOnScaleGestureListener() {

        override fun onScale(detector: ScaleGestureDetector): Boolean {
            mScaleFactor *= detector.scaleFactor
            mScaleFactor = mScaleFactor.coerceAtLeast(0.1f).coerceAtMost(5.0f)
            // Don't let the object get too small or too large.
            val textSize = (config?.textSize ?: DEFAULT_TEXT_SIZE) * if (mScaleFactor > 1f) {
                ((1 - mScaleFactor).absoluteValue * 0.1f + 1f)
            } else {
                1 - (1 - mScaleFactor).absoluteValue * 0.1f
            }
            if (textSize > MAX_TEXT_SIZE && mScaleFactor > 1f) {
                Log.i(TAG, "onScale: $textSize, $mScaleFactor, to max")
                return true
            }
            if (textSize < MIN_TEXT_SIZE && mScaleFactor < 1f) {
                Log.i(TAG, "onScale: $textSize, $mScaleFactor, to min")
                return true
            }
            Log.i(TAG, "onScale $mScaleFactor, textSize: ${config?.textSize} ==> $textSize")
            config = config?.copy(textSize = textSize)
            invalidate()
            return true
        }

        override fun onScaleEnd(detector: ScaleGestureDetector) {
            super.onScaleEnd(detector)
            Log.i(TAG, "onScaleEnd $mScaleFactor")
            val textSize = (config?.textSize ?: DEFAULT_TEXT_SIZE)
//            config = config?.copy(textSize = textSize)
//            mScaleFactor = 1f
            this@WaterMarkImageView.onScaleEnd(textSize)
        }
    }

    private var startX = 0f
    private var startY = 0f
    private var enableTouch = true

    override fun onTouchEvent(event: MotionEvent): Boolean {
        // Let the ScaleGestureDetector inspect all events.
        Log.i(TAG, "onTouch $event")
        if (enableTouch.not()) {
            return false
        }
//        mScaleDetector.onTouchEvent(event)
//        if (mScaleDetector.isInProgress) {
//            return true
//        }
        if (isTouchWaterMark(event).not() || event.pointerCount > 1) {
            return true
        }
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                startX = event.x
                startY = event.y
            }
            MotionEvent.ACTION_MOVE -> {
                val endX = event.x
                val endY = event.y
                val deltaX = endX - startX
                val deltaY = endY - startY
                updateWaterMarkOffset(deltaX, deltaY)
                this.startX = endX
                this.startY = endY
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                val endX = event.x
                val endY = event.y
                val deltaX = endX - startX
                val deltaY = endY - startY
                if (isOutOfDrawable(deltaX, deltaY)) {
                    // disable touch
                    enableTouch = false
                    // begin back to center animation
                    backToCenter { info, x, y ->
                        this.startX = x
                        this.startY = y
                        enableTouch = true
                        onOffsetChanged(info)
                    }
                } else {
                    this.startX = endX
                    this.startY = endY
                    onOffsetChanged(curImageInfo)
                }
            }
        }
        return true
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
            bitmapHeight: Int,
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

        // adjustHorizonalGap/adjustVerticalGap/calculateMaxSize removed — cell sizing now lives in
        // commonMain WatermarkGeometry (C2a), used by both buildTextBitmapShader + buildIconBitmapShader.

        fun calculateDrawLimitWidth(w: Int, ps: Int) = (w - ps * 2)

        fun calculateDrawLimitHeight(h: Int, pt: Int) = (h - pt * 2)

        /**
         * S2a: compatibility wrapper. The icon-cell builder body now lives in the Android renderer
         * seam [WatermarkRenderer.buildIconShader]; this wrapper is retained so existing call sites
         * and goldens (which reference `WaterMarkImageView.buildIconBitmapShader`) stay unchanged.
         */
        suspend fun buildIconBitmapShader(
            imageInfo: ImageInfo,
            srcBitmap: Bitmap,
            config: WaterMark,
            textPaint: Paint,
            scale: Boolean,
            coroutineContext: CoroutineContext,
        ): WaterMarkShader? =
            WatermarkRenderer.buildIconShader(
                imageInfo, srcBitmap, config, textPaint, scale, coroutineContext
            )

        /**
         * S2a: compatibility wrapper. The text-cell builder body now lives in the Android renderer
         * seam [WatermarkRenderer.buildTextShader]; this wrapper is retained so existing call sites
         * and goldens (which reference `WaterMarkImageView.buildTextBitmapShader`) stay unchanged.
         */
        suspend fun buildTextBitmapShader(
            imageInfo: ImageInfo,
            config: WaterMark,
            textPaint: TextPaint,
            coroutineContext: CoroutineContext,
        ): WaterMarkShader? =
            WatermarkRenderer.buildTextShader(imageInfo, config, textPaint, coroutineContext)
    }
}
