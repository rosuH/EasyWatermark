package me.rosuh.easywatermark.widget

import android.content.Context
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import androidx.core.view.children
import androidx.recyclerview.widget.LinearSnapHelper
import androidx.recyclerview.widget.RecyclerView
import kotlin.math.abs

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

    val snapHelper: LinearSnapHelper by lazy {
        LinearSnapHelper()
    }

    private var onSnapViewSelected: (snapView: View, pos: Int) -> Unit = { _, _ -> }

    private var onSnapViewPreview: (snapView: View, pos: Int) -> Unit = { _, _ -> }

    fun onSnapViewSelected(block: (snapView: View, pos: Int) -> Unit) {
        this.onSnapViewSelected = block
    }

    fun onSnapViewPreview(block: (snapView: View, pos: Int) -> Unit) {
        this.onSnapViewPreview = block
    }


    init {
        snapHelper.attachToRecyclerView(this)
        addOnScrollListener(object : RecyclerView.OnScrollListener() {
            private var debounceTs = 0L
            override fun onScrollStateChanged(recyclerView: RecyclerView, newState: Int) {
                super.onScrollStateChanged(recyclerView, newState)
                val snapView = snapHelper.findSnapView(layoutManager)
                when (newState) {
                    RecyclerView.SCROLL_STATE_IDLE -> {
                        if (snapView == null
                            || !canAutoSelected
                            || System.currentTimeMillis() - debounceTs < 120
                        ) {
                            canAutoSelected = true
                            return
                        }
                        debounceTs = System.currentTimeMillis()
                        val pos = getChildLayoutPosition(snapView)
                        onSnapViewSelected.invoke(snapView, pos)
                    }
                }
            }

            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                super.onScrolled(recyclerView, dx, dy)
                if (recyclerView.scrollState != SCROLL_STATE_DRAGGING) {
                    return
                }
                val snapView = snapHelper.findSnapView(layoutManager) ?: return
                val dX = abs(width / 2 - (snapView.left + snapView.right) / 2)
                if (dX <= 1) {
                    val pos = getChildLayoutPosition(snapView)
                    onSnapViewPreview.invoke(snapView, pos)
                }
            }
        })
    }

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