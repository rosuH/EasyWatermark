package me.rosuh.easywatermark.ui.widget

import android.content.Context
import android.util.AttributeSet
import android.util.DisplayMetrics
import android.view.View
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearSmoothScroller
import androidx.recyclerview.widget.RecyclerView

class UniformScrollGridLayoutManager : GridLayoutManager {

    constructor(
        context: Context?,
        attrs: AttributeSet?,
        defStyleAttr: Int,
        defStyleRes: Int
    ) : super(context, attrs, defStyleAttr, defStyleRes)

    constructor(context: Context?, spanCount: Int) : super(context, spanCount)
    constructor(
        context: Context?,
        spanCount: Int,
        orientation: Int,
        reverseLayout: Boolean
    ) : super(context, spanCount, orientation, reverseLayout)

    var enable: Boolean = true

    var speed = 1.2f

    var scrollBarView: View? = null

    override fun smoothScrollToPosition(
        recyclerView: RecyclerView?,
        state: RecyclerView.State?,
        position: Int
    ) {
        if (enable) {
            val linearSmoothScroller =
                object : LinearSmoothScroller(recyclerView!!.context) {
                    override fun calculateSpeedPerPixel(displayMetrics: DisplayMetrics?): Float {
                        return speed
                    }
                }
            linearSmoothScroller.targetPosition = position
            startSmoothScroll(linearSmoothScroller)
        } else {
            super.smoothScrollToPosition(recyclerView, state, position)
        }
    }

    override fun computeVerticalScrollExtent(state: RecyclerView.State): Int {
        return scrollBarView?.measuredHeight ?: 110
    }
}