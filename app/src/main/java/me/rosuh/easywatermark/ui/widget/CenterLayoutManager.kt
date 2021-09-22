package me.rosuh.easywatermark.ui.widget

import android.content.Context
import android.util.AttributeSet
import android.util.DisplayMetrics
import android.util.Log
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.LinearSmoothScroller
import androidx.recyclerview.widget.RecyclerView

/**
 * https://stackoverflow.com/questions/32241948/how-can-i-control-the-scrolling-speed-of-recyclerview-smoothscrolltopositionpos
 */
class CenterLayoutManager : LinearLayoutManager {
    constructor(context: Context) : super(context)
    constructor(context: Context, orientation: Int, reverseLayout: Boolean) : super(
        context,
        orientation,
        reverseLayout
    )

    constructor(context: Context, attrs: AttributeSet, defStyleAttr: Int, defStyleRes: Int) : super(
        context,
        attrs,
        defStyleAttr,
        defStyleRes
    )

    override fun smoothScrollToPosition(
        recyclerView: RecyclerView,
        state: RecyclerView.State,
        position: Int
    ) {
        val centerSmoothScroller = CenterSmoothScroller(recyclerView.context).apply {
            onStartSmoothScroll {
                this@CenterLayoutManager.onStartSmoothScroll.invoke()
            }
            onStopSmoothScroll {
                this@CenterLayoutManager.onStopSmoothScroll.invoke()
            }
        }
        centerSmoothScroller.targetPosition = position
        startSmoothScroll(centerSmoothScroller)
    }

    private var onStartSmoothScroll: () -> Unit = {}

    private var onStopSmoothScroll: () -> Unit = {}

    fun onStartSmoothScroll(block: () -> Unit) {
        this.onStartSmoothScroll = block
    }

    fun onStopSmoothScroll(block: () -> Unit) {
        this.onStopSmoothScroll = block
    }

    internal class CenterSmoothScroller(context: Context) : LinearSmoothScroller(context) {

        private val speedFac = 2.5f

        override fun calculateDtToFit(
            viewStart: Int,
            viewEnd: Int,
            boxStart: Int,
            boxEnd: Int,
            snapPreference: Int
        ): Int = (boxStart + (boxEnd - boxStart) / 2) - (viewStart + (viewEnd - viewStart) / 2)

        override fun calculateSpeedPerPixel(displayMetrics: DisplayMetrics?): Float {
            return super.calculateSpeedPerPixel(displayMetrics) * speedFac
        }


        private var onStartSmoothScroll: () -> Unit = {}

        private var onStopSmoothScroll: () -> Unit = {}

        fun onStartSmoothScroll(block: () -> Unit) {
            this.onStartSmoothScroll = block
        }

        fun onStopSmoothScroll(block: () -> Unit) {
            this.onStopSmoothScroll = block
        }

        override fun onStart() {
            super.onStart()
            onStartSmoothScroll.invoke()
            Log.i("CenterSmoothScroller", "onStart")
        }

        override fun onStop() {
            super.onStop()
            onStopSmoothScroll.invoke()
            Log.i("CenterSmoothScroller", "onStop")
        }
    }
}
