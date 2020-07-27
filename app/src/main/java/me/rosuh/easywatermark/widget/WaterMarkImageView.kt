package me.rosuh.easywatermark.widget

import android.content.Context
import android.graphics.*
import android.text.TextPaint
import android.util.AttributeSet
import me.rosuh.easywatermark.BuildConfig
import me.rosuh.easywatermark.config.WaterMarkConfig
import me.rosuh.easywatermark.ktx.applyConfig
import kotlin.math.pow
import kotlin.math.sqrt

class WaterMarkImageView : androidx.appcompat.widget.AppCompatImageView {
    constructor(context: Context) : super(context)
    constructor(context: Context, attrs: AttributeSet?) : super(context, attrs)
    constructor(context: Context, attrs: AttributeSet?, defStyleAttr: Int) : super(
        context,
        attrs,
        defStyleAttr
    )

    var config: WaterMarkConfig? = null
        set(value) {
            field = value
            setImageURI(value?.uri)
            paint.applyConfig(value)
            if (field != null && !field?.text.isNullOrEmpty() && field?.uri.toString().isNotEmpty()
            ) {
                paint.getTextBounds(config!!.text, 0, config!!.text.length, bounds)
                layoutShader = buildTextBitmapShader(field!!, bounds, paint)
            }
            invalidate()
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

    override fun onDraw(canvas: Canvas?) {
        super.onDraw(canvas)
        if (config?.text.isNullOrEmpty() || config?.uri.toString().isEmpty()) {
            return
        }
        layoutPaint.shader = layoutShader
        canvas?.drawRect(0f, 0f, measuredWidth.toFloat(), measuredHeight.toFloat(), layoutPaint)
    }

    companion object {
        fun buildTextBitmapShader(
            config: WaterMarkConfig,
            textBounds: Rect,
            textPaint: Paint
        ): BitmapShader? {
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

            return BitmapShader(bitmap!!, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT)
        }
    }
}