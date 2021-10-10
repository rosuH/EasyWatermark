package me.rosuh.easywatermark.ui.widget

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.util.AttributeSet
import android.util.Log
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import androidx.core.view.children
import androidx.core.view.marginBottom
import androidx.core.view.marginEnd
import androidx.core.view.marginTop
import androidx.interpolator.view.animation.FastOutSlowInInterpolator
import me.rosuh.easywatermark.R
import me.rosuh.easywatermark.utils.VibrateHelper
import me.rosuh.easywatermark.utils.ktx.dp

class PhotoSavingItem : FrameLayout {


//    constructor(context: Context?) : super(context)
//    constructor(context: Context?, attrs: AttributeSet?) : super(context, attrs)
//    constructor(context: Context?, attrs: AttributeSet?, defStyleAttr: Int) : super(
//        context,
//        attrs,
//        defStyleAttr
//    )
//
//    constructor(
//        context: Context?,
//        attrs: AttributeSet?,
//        defStyleAttr: Int,
//        defStyleRes: Int
//    ) : super(context, attrs, defStyleAttr, defStyleRes)

    private var isLongPress = false

    val ivIcon: ProgressImageVIew by lazy {
        ProgressImageVIew(context).apply {
            id = View.generateViewId()
            setBackgroundColor(Color.WHITE)
            isLongPress = false
            scaleType = ImageView.ScaleType.CENTER_CROP
            MarginLayoutParams(
                MarginLayoutParams.MATCH_PARENT,
                MarginLayoutParams.MATCH_PARENT
                )
            adjustViewBounds = true
        }
    }

    val ivDone: ImageView by lazy {
        ImageView(context).apply {
            id = View.generateViewId()
            layoutParams =
                MarginLayoutParams(
                    MarginLayoutParams.WRAP_CONTENT,
                    MarginLayoutParams.WRAP_CONTENT
                ).also {
                    it.marginEnd = 4.dp
                    it.bottomMargin = 4.dp
                }
            scaleType = ImageView.ScaleType.CENTER_CROP
            setImageResource(R.drawable.ic_save_done)
        }
    }

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

    init {
        clipToPadding = false
        clipChildren = false
        layoutParams = MarginLayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply {
            marginStart = 5.dp
            marginEnd = 5.dp
            topMargin = 13.dp
        }
        setBackgroundColor(Color.RED)
        addView(ivDone)
        addView(ivIcon)
    }

//    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
//        children.forEach {
//            measureChildWithMargins(it, widthMeasureSpec, 0, heightMeasureSpec, 0)
//        }
//        setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), MeasureSpec.getSize(heightMeasureSpec) * 2)
//    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        ivIcon.let {
            it.layout(
                (measuredWidth - it.measuredWidth) / 2,
                (measuredHeight - it.measuredHeight) / 2,
                (measuredWidth - it.measuredWidth) / 2 + it.measuredWidth,
                (measuredWidth - it.measuredWidth) / 2 + it.measuredHeight
            )
        }
        ivDone.let {
            it.layout(
                (measuredWidth - it.marginEnd - it.measuredWidth) / 2,
                measuredHeight - it.marginBottom - it.measuredHeight,
                measuredWidth,
                measuredHeight - it.marginBottom
            )
        }
    }
}