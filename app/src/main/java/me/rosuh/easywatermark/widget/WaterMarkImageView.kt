package me.rosuh.easywatermark.widget

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.util.AttributeSet
import me.rosuh.easywatermark.config.WaterMarkConfig
import me.rosuh.easywatermark.ktx.applyConfig
import kotlin.math.roundToInt

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
            invalidate()
        }


    private val paint: Paint by lazy {
        Paint().applyConfig(config)
    }

    private var horizonCount = 0
    private var verticalCount = 0
    private var textHeight = 0f
    private var textWidth = 0f
    private var bounds: Rect = Rect()

    init {
        setImageURI(config?.uri)
    }

    private fun maxRadius() = WaterMarkConfig.calculateCircleRadius(measuredWidth, measuredHeight)

    override fun onDraw(canvas: Canvas?) {
        super.onDraw(canvas)
        if (config?.text.isNullOrEmpty()) {
            return
        }
        canvas?.save()
        canvas?.rotate(
            config!!.degree,
            (measuredWidth / 2).toFloat(),
            (measuredHeight / 2).toFloat()
        )

        paint.getTextBounds(config!!.text, 0, config!!.text.length, bounds)
        textWidth = bounds.width().toFloat()
        textHeight = bounds.height().toFloat()
        horizonCount =
            (maxRadius() / (textWidth + config!!.calculateHorizon(measuredWidth))).roundToInt()
        verticalCount =
            (maxRadius() / (textHeight + config!!.calculateVertical(measuredHeight))).roundToInt()
        for (iX in 0..horizonCount) {
            for (iY in 0..verticalCount) {
                canvas?.drawText(
                    config!!.text,
                    iX * (textWidth + (if (iX == 0) 0 else config!!.calculateHorizon(measuredWidth))),
                    iY * (textHeight + (if (iY == 0) 0 else config!!.calculateVertical(
                        measuredHeight
                    ))),
                    paint
                )
            }
        }
        canvas?.restore()
    }
}