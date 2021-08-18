package me.rosuh.easywatermark.widget

import android.content.Context
import android.util.AttributeSet
import android.view.View
import android.view.ViewGroup
import androidx.core.view.*

/**
 * An Custom ViewGroup abstract class for convenience.
 * @author rosuh@qq.com
 * @date 2021/8/12
 */
abstract class CustomViewGroup : ViewGroup {
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

    protected fun getLayoutParamsWithMargin(
        width: Int,
        height: Int,
        l: Int = 0,
        t: Int = 0,
        r: Int = 0,
        b: Int = 0
    ): MarginLayoutParams {
        return MarginLayoutParams(width, height).apply {
            setMargins(l, t, r, b)
        }
    }

    val View.measuredWidthWithMargins: Int
        get() {
            return (measuredWidth + marginLeft + marginRight)
        }
    val View.measuredHeightWithMargins: Int
        get() {
            return (measuredHeight + marginTop + marginBottom)
        }

    protected fun View.layoutCenterHorizontal(appendY: Int = 0) {
        val startX = (this@CustomViewGroup.measuredWidth - this.measuredWidth) / 2
        layout(
            startX + this@CustomViewGroup.paddingStart + this.marginStart,
            this@CustomViewGroup.paddingTop + this.marginTop + appendY
        )
    }

    protected fun View.layoutCenterVertical(appendX: Int = 0) {
        val startY = (this@CustomViewGroup.measuredHeight - this.measuredHeight) / 2
        layout(
            this@CustomViewGroup.paddingStart + this.marginStart + appendX,
            startY + this@CustomViewGroup.paddingTop + this.marginTop
        )
    }

    protected fun View.layout(x: Int, y: Int) {
        layout(x, y, x + this.measuredWidth, y + this.measuredHeight)
    }


    override fun generateDefaultLayoutParams(): LayoutParams {
        return MarginLayoutParams(
            LayoutParams.WRAP_CONTENT,
            LayoutParams.WRAP_CONTENT
        )
    }
}