package me.rosuh.easywatermark.ui.widget

import android.content.Context
import android.util.AttributeSet
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import androidx.constraintlayout.utils.widget.ImageFilterView
import androidx.core.view.children
import me.rosuh.easywatermark.utils.ktx.colorSurface
import me.rosuh.easywatermark.utils.ktx.dp


class GalleryItemView : FrameLayout {

    constructor(context: Context) : super(context)
    constructor(context: Context, attrs: AttributeSet?) : super(context, attrs)
    constructor(context: Context, attrs: AttributeSet?, defStyleAttr: Int) : super(
        context,
        attrs,
        defStyleAttr
    )

    constructor(
        context: Context,
        attrs: AttributeSet?,
        defStyleAttr: Int,
        defStyleRes: Int
    ) : super(context, attrs, defStyleAttr, defStyleRes)

    val ivImage: ImageFilterView by lazy {
        ImageFilterView(context).apply {
            layoutParams = MarginLayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            scaleType = ImageView.ScaleType.CENTER_CROP
            addView(this)
        }
    }


    val radioButton: RadioButton by lazy {
        RadioButton(context).apply {
            layoutParams = MarginLayoutParams(
                24.dp,
                24.dp
            ).also { it.setMargins(4.dp, 4.dp, 0, 0) }
            addView(this)
        }
    }

    init {
        setBackgroundColor(context.colorSurface)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        super.onMeasure(widthMeasureSpec, widthMeasureSpec)
        measureChildWithMargins(ivImage, widthMeasureSpec, 0, heightMeasureSpec, 0)
        measureChildWithMargins(radioButton, widthMeasureSpec, 0, heightMeasureSpec, 0)
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        children.forEach {
            it.also { v ->
                v.layout(0, 0, it.measuredWidth, it.measuredHeight)
            }
        }
    }
}