package me.rosuh.easywatermark.widget

import android.content.ContentResolver
import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.rosuh.easywatermark.BuildConfig
import me.rosuh.easywatermark.ktx.applyConfig
import me.rosuh.easywatermark.model.WaterMarkConfig
import kotlin.coroutines.CoroutineContext
import kotlin.math.pow
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


    var config: WaterMarkConfig? = null
        set(value) {
            field = value
            launch {
                setImageURI(value?.uri)
                paint.applyConfig(value)
                if (field != null && !field?.text.isNullOrEmpty() && field?.uri.toString()
                        .isNotEmpty()
                ) {
                    paint.getTextBounds(config!!.text, 0, config!!.text.length, bounds)
                    layoutShader = when (field!!.markMode) {
                        WaterMarkConfig.MarkMode.Text -> {
                            buildTextBitmapShader(
                                field!!,
                                bounds,
                                paint
                            )
                        }
                        WaterMarkConfig.MarkMode.Image -> {
                            buildIconBitmapShader(
                                context.contentResolver,
                                limitBounds,
                                field!!,
                                paint
                            )
                        }
                    }
                }
                invalidate()
            }
        }

    private val paint: Paint by lazy {
        Paint().applyConfig(config)
    }

    private val layoutPaint: Paint by lazy {
        Paint()
    }

    private var bounds: Rect = Rect()

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
        canvas?.drawRect(0f, 0f, measuredWidth.toFloat(), measuredHeight.toFloat(), layoutPaint)
    }

    companion object {
        suspend fun buildIconBitmapShader(
            contentResolver: ContentResolver,
            limitBounds: Rect,
            config: WaterMarkConfig,
            textPaint: Paint
        ): BitmapShader? = withContext(Dispatchers.IO) {
            if (config.iconBitmap == null) {
                return@withContext null
            }

            val proportion = limitBounds.width().toFloat() / limitBounds.height()
            val isLand = limitBounds.width() > limitBounds.height()
            val rawWidth = config.iconBitmap!!.width.toFloat().coerceAtLeast(1f).coerceAtMost(limitBounds.width().toFloat())
            val rawHeight = config.iconBitmap!!.height.toFloat().coerceAtLeast(1f).coerceAtMost(limitBounds.height().toFloat())

            val iconW = if (isLand) {
                rawWidth
            } else {
                proportion * rawHeight
            }
            val iconH = if (isLand) {
                rawWidth / proportion
            } else {
                rawHeight
            }

            val maxSize = sqrt(iconH.pow(2) + iconW.pow(2)).toInt()
            val finalWidth = maxSize + config.horizonGapPercent
            val finalHeight = maxSize + config.verticalGapPercent
            val targetBitmap = Bitmap.createBitmap(finalWidth, finalHeight, Bitmap.Config.ARGB_8888)

            val canvas = Canvas(targetBitmap)
            val percent = textPaint.textSize / 100
            val options = BitmapFactory.Options().apply {
                inSampleSize = percent.toInt()
            }
            val inputStream = contentResolver.openInputStream(config.iconUri)!!
            val scaleBitmap = BitmapFactory.decodeStream(inputStream, null, options)!!

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

            canvas.drawBitmap(
                scaleBitmap,
                (finalWidth - config.iconBitmap!!.width * percent) / 2.toFloat(),
                (finalHeight - config.iconBitmap!!.height * percent) / 2.toFloat(),
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
            textPaint: Paint
        ): BitmapShader? = withContext(Dispatchers.IO) {
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