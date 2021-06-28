package me.rosuh.easywatermark.widget

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import androidx.core.content.ContextCompat
import me.rosuh.easywatermark.R
import me.rosuh.easywatermark.ktx.dp

class VerticalDashLineView : View {

    constructor(context: Context?) : super(context)
    constructor(context: Context?, attrs: AttributeSet?) : super(context, attrs)
    constructor(context: Context?, attrs: AttributeSet?, defStyleAttr: Int) : super(
        context,
        attrs,
        defStyleAttr
    )

    constructor(
        context: Context?,
        attrs: AttributeSet?,
        defStyleAttr: Int,
        defStyleRes: Int
    ) : super(context, attrs, defStyleAttr, defStyleRes)

    private val paint by lazy {
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            isDither = true
            color = ContextCompat.getColor(context, R.color.d_neutral_2)
            strokeWidth = 2.dp
            strokeCap = Paint.Cap.ROUND
        }
    }

    override fun onDraw(canvas: Canvas?) {
        super.onDraw(canvas)
        canvas?.drawCircle((width / 2).toFloat(), (height / 2).toFloat(), 3.dp, paint)
//        canvas?.drawLine(
//            (width / 2).toFloat(),
//            0f,
//            (width / 2).toFloat(),
//            (height).toFloat(),
//            paint
//        )
    }
}