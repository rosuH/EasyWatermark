package me.rosuh.easywatermark.ui.widget

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.util.AttributeSet
import android.view.View
import androidx.core.content.ContextCompat
import com.google.android.material.color.MaterialColors
import me.rosuh.easywatermark.R
import me.rosuh.easywatermark.utils.ktx.colorOnSurface
import me.rosuh.easywatermark.utils.ktx.colorOnTertiaryContainer
import me.rosuh.easywatermark.utils.ktx.colorSurfaceVariant
import me.rosuh.easywatermark.utils.ktx.colorTertiaryContainer
import me.rosuh.easywatermark.utils.ktx.dp
import me.rosuh.easywatermark.utils.ktx.getColorFromAttr

class RadioButton : View {

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

    companion object {
        private const val TAG = "RadioButton"
    }

    private val icTint = ContextCompat.getColor(context, R.color.selector_gallery_icon_tint)

    private val bgColorNormal = Color.TRANSPARENT
    private val bgColorSelected
        get() = context.colorTertiaryContainer

    private val strokeColorNormal by lazy {
        MaterialColors.compositeARGBWithAlpha(
            context.getColorFromAttr(R.attr.colorBackgroundFloating),
            125
        )
    }
    private val strokeColorSelected
        get() = context.colorSurfaceVariant
    private val strokeWidth = 2.dp

    private val iconRes: Int = R.drawable.ic_gallery_radio_button

    private var icon: Drawable? = null

    private val paint = Paint().apply {
        isDither = true
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        icon = generateIcon(w, h)
    }

    private fun generateIcon(w: Int, h: Int): Drawable {
        return ContextCompat.getDrawable(context, iconRes) ?: ColorDrawable(context.colorOnSurface)
    }

    override fun onDraw(canvas: Canvas) {
        // draw background
        paint.style = Paint.Style.FILL
        paint.color = if (isChecked) bgColorSelected else bgColorNormal
        paint.strokeWidth = 0f
        canvas?.drawCircle(
            measuredWidth / 2f,
            measuredHeight / 2f,
            (measuredWidth - strokeWidth) / 2f,
            paint
        )
        // draw stroke
        paint.style = Paint.Style.STROKE
        paint.color = if (isChecked) strokeColorSelected else strokeColorNormal
        paint.strokeWidth = strokeWidth.toFloat()
        canvas?.drawCircle(
            measuredWidth / 2f,
            measuredHeight / 2f,
            (measuredWidth - strokeWidth) / 2f,
            paint
        )
        // icon
        if (canvas != null && isChecked) {
            icon?.setBounds(0, 0, (measuredWidth), (measuredHeight))
            icon?.setTint(context.colorOnTertiaryContainer)
            icon?.draw(canvas)
        }
    }

    var isChecked = false
        set(value) {
            if (field != value) {
                invalidate()
                listener.invoke(value)
            }
            field = value
        }

    fun toggle() {
        isChecked = !isChecked
    }

    private var listener: (isCheck: Boolean) -> Unit = {}

    fun setOnCheckedChangeListener(listener: (isCheck: Boolean) -> Unit) {
        this.listener = listener
    }
}