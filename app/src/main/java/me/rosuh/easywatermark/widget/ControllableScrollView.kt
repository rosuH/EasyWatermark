package me.rosuh.easywatermark.widget

import android.annotation.SuppressLint
import android.content.Context
import android.util.AttributeSet
import androidx.core.widget.NestedScrollView

/**
 * @author rosuh@qq.com
 * @date 2020/9/11
 * 允许外部控制是否滚动，即便是在 onLayout 的时候。但不会阻止由用户触摸产生的滑动
 * Allow external control whether to scroll, even when onLayout was called.
 * But does not prevent swipes caused by user touch
 */
@SuppressLint("ClickableViewAccessibility")
class ControllableScrollView : NestedScrollView {
    constructor(context: Context) : super(context)
    constructor(context: Context, attrs: AttributeSet?) : super(context, attrs)
    constructor(context: Context, attrs: AttributeSet?, defStyleAttr: Int) : super(
        context,
        attrs,
        defStyleAttr
    )

    var canScroll = true

    override fun scrollTo(x: Int, y: Int) {
        if (canScroll) {
            super.scrollTo(x, y)
        }
    }

    override fun scrollBy(x: Int, y: Int) {
        if (canScroll) {
            super.scrollBy(x, y)
        }
    }
}