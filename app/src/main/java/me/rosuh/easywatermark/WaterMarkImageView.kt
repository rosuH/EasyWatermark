package me.rosuh.easywatermark

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sqrt

class WaterMarkImageView : androidx.appcompat.widget.AppCompatImageView {
    constructor(context: Context?) : super(context)
    constructor(context: Context?, attrs: AttributeSet?) : super(context, attrs)
    constructor(context: Context?, attrs: AttributeSet?, defStyleAttr: Int) : super(
        context,
        attrs,
        defStyleAttr
    )

    val config by lazy { WaterMarkConfig() }

    private var degree: Float = 0f

    private val paint: Paint by lazy {
        Paint().apply {
            strokeWidth = 1f
            color = Color.RED
            alpha = 78
            textSize = 48f
            isAntiAlias = true
            style = Paint.Style.STROKE
            isDither = true
        }
    }

    private var horizonCount = 0

    private var verticalCount = 0

    var waterText: String = ""
        set(value) {
            field = value
            config.text = waterText
            invalidate()
        }

    private var textHeight = 0f
    private var textWidth = 0f
    private var bounds: Rect = Rect()

    override fun onDraw(canvas: Canvas?) {
        super.onDraw(canvas)
        if (waterText.isEmpty()) {
            return
        }
        canvas?.save()
        canvas?.rotate(degree, (measuredWidth / 2).toFloat(), (measuredHeight / 2).toFloat())

        paint.getTextBounds(waterText, 0, waterText.length, bounds)
        textWidth = bounds.width().toFloat()
        textHeight = bounds.height().toFloat()
        horizonCount = (getMaxSize() / (textWidth + config.horizonGap)).roundToInt()
        verticalCount = (getMaxSize() / (textHeight + config.verticalGap)).roundToInt()
        for (iX in 0..horizonCount) {
            for (iY in 0..verticalCount) {
                canvas?.drawText(
                    waterText,
                    iX * (textWidth + (if (iX == 0) 0 else config.horizonGap)),
                    iY * (textHeight + (if (iY == 0) 0 else config.verticalGap)),
                    paint
                )
            }

        }
        canvas?.restore()

    }

    private fun getMaxSize(): Float {
        return sqrt(measuredHeight.toFloat().pow(2) + measuredWidth.toFloat().pow(2))
    }

    fun adjustHorizon(percent: Float) {
        config.horizonGap =
            (percent * measuredWidth).toInt().coerceAtLeast(0).coerceAtMost(measuredWidth)
        invalidate()
    }

    fun adjustVertical(percent: Float) {
        config.verticalGap =
            (percent * measuredHeight).toInt().coerceAtLeast(0).coerceAtMost(measuredHeight)
        invalidate()
    }

    fun textAlpha(percent: Float) {
        config.alpha = (percent * 255).toInt().coerceAtLeast(0).coerceAtMost(255)
        paint.alpha = config.alpha
        invalidate()
    }

    fun textRotate(degree: Float) {
        config.degree = degree
        this.degree = config.degree
        invalidate()
    }
}