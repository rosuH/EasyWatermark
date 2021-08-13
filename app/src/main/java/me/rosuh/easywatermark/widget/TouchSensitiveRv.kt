package me.rosuh.easywatermark.widget

import android.content.Context
import android.util.AttributeSet
import android.view.MotionEvent
import androidx.core.view.children
import androidx.recyclerview.widget.RecyclerView

class TouchSensitiveRv : RecyclerView {

    constructor(context: Context) : super(context)
    constructor(context: Context, attrs: AttributeSet?) : super(context, attrs)
    constructor(context: Context, attrs: AttributeSet?, defStyleAttr: Int) : super(
        context,
        attrs,
        defStyleAttr
    )

    var isTouching = false

    // toggle whether if scroll listener can detected snap view to handle selected item
    var canAutoSelected = true

    override fun onMeasure(widthSpec: Int, heightSpec: Int) {
        super.onMeasure(widthSpec, heightSpec)
        val w = children.firstOrNull()?.measuredWidth ?: 0
        setPadding((measuredWidth - w) / 2, 0, (measuredWidth - w) / 2, 0)
    }

    override fun dispatchTouchEvent(ev: MotionEvent?): Boolean {
        when (ev?.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                isTouching = true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                isTouching = false
            }
        }
        return super.dispatchTouchEvent(ev)
    }
}