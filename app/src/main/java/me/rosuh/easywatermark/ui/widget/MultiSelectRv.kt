package me.rosuh.easywatermark.ui.widget

import android.content.Context
import android.hardware.display.DisplayManager
import android.os.Handler
import android.os.Looper
import android.util.AttributeSet
import android.util.Log
import android.view.GestureDetector
import android.view.MotionEvent
import androidx.core.view.GestureDetectorCompat
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlin.math.abs

/**
 * 支持滑动多选的 RecyclerView
 * RecyclerView that supports sliding multiple selection
 */
class MultiSelectRv : RecyclerView {

    companion object {
        private const val TAG = "MultiSelectRv"
    }

    constructor(context: Context) : super(context)
    constructor(context: Context, attrs: AttributeSet?) : super(context, attrs)
    constructor(context: Context, attrs: AttributeSet?, defStyleAttr: Int) : super(
        context,
        attrs,
        defStyleAttr
    )

    private var onSelect: ((rv: RecyclerView, end: Int) -> Unit)? = null

    private var onUnSelect: ((rv: RecyclerView, end: Int) -> Unit)? = null

    private var refreshRate: Float = 60f

    fun setOnSelect(onSelect: (rv: RecyclerView, end: Int) -> Unit) {
        this.onSelect = onSelect
    }

    fun setOnUnSelect(onUnSelect: (rv: RecyclerView, end: Int) -> Unit) {
        this.onUnSelect = onUnSelect
    }


    init {
        if (!isInEditMode) {
            val displayManager: DisplayManager =
                context.applicationContext.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
            refreshRate = displayManager.displays?.getOrNull(0)?.refreshRate ?: 60F

            addOnItemTouchListener(object : OnItemTouchListener {
                private var leftArea: Boolean = false
                private var scrollTopArea: Boolean = false
                private var scrollBottomArea: Boolean = false
                private var latestMoveTs: Long = 0
                private var preTouchPos: Int = -1
                private var preMoveX = 0f
                private var preMoveY = 0f
                private var downY = 0f
                private var downX = 0f
                private var isIncreasing: Boolean = false
                private var touchPos: Int = -1
                private var isInAutoScrollArea = false
                private var isLongPress = false
                private var isAutoScrolling = false
                private var autoScroll: Runnable? = null
                private val handle = Handler(Looper.getMainLooper())
                private var startPressPos = 0

                val gestureDetector = GestureDetectorCompat(
                    context,
                    object : GestureDetector.SimpleOnGestureListener() {
                        override fun onLongPress(e: MotionEvent) {
                            super.onLongPress(e)
                            isLongPress = true
                        }
                    })

                override fun onInterceptTouchEvent(rv: RecyclerView, e: MotionEvent): Boolean {
                    gestureDetector.onTouchEvent(e)
                    return isLongPress
                }

                override fun onTouchEvent(rv: RecyclerView, event: MotionEvent) {
                    val gridLayoutManager = (rv.layoutManager as? GridLayoutManager?) ?: return
                    when (event.actionMasked) {
                        MotionEvent.ACTION_DOWN -> {
                            Log.i(TAG, "ACTION_DOWN")
                            downX = event.x
                            downY = event.y
                            isLongPress = false
                            isAutoScrolling = false
                            isInAutoScrollArea = false
                            isIncreasing = false
                            rv.findChildViewUnder(downX, downY)?.let {
                                startPressPos = gridLayoutManager.getPosition(it)
                            }
                            rv.stopScroll()
                        }
                        MotionEvent.ACTION_MOVE -> {
                            latestMoveTs = System.currentTimeMillis()
                            val moveX = event.x
                            val moveY = event.y
                            val touchItem = rv.findChildViewUnder(moveX, moveY)
                            val distanceX = moveX - preMoveX
                            val distanceY = moveY - preMoveY
                            val distanceStartX = abs(moveX - downX)
                            val distanceStartY = abs(moveY - downY)
                            touchItem?.let {
                                touchPos = gridLayoutManager.getPosition(touchItem)
                                scrollBottomArea =
                                    rv.bottom - moveY < it.measuredHeight * 2
                                scrollTopArea =
                                    moveY - rv.top < it.measuredHeight * 2
                                leftArea = moveX < rv.measuredWidth / 2
                                isInAutoScrollArea = scrollBottomArea || scrollTopArea
                                val increase =
                                    (distanceX > 0 || distanceY > 0) && (preTouchPos < touchPos || distanceX >= it.measuredWidth || distanceY >= it.measuredHeight)
                                val reduce =
                                    (distanceX < 0 || distanceY < 0) && (preTouchPos > touchPos || distanceX >= it.measuredWidth || distanceY >= it.measuredHeight)

                                Log.i(
                                    TAG,
                                    "rvGestureDetector onScroll reduce = $reduce, increase = $increase, distanceX = $distanceX, distanceY = $distanceY, $preTouchPos, $touchPos, dx = $distanceStartX, distanceStartY = $distanceStartY, scrollBottomArea = $scrollBottomArea, scrollTopArea = $scrollTopArea, leftArea = $leftArea, isInAutoScrollArea = $isInAutoScrollArea"
                                )
                                preTouchPos = touchPos
                                when {
                                    scrollBottomArea && increase -> {
                                        Log.i(TAG, "scrollBottomArea")
                                        onSelect?.invoke(
                                            rv,
                                            gridLayoutManager.findLastVisibleItemPosition()
                                        )
                                        preMoveX = moveX
                                        preMoveY = moveY
                                    }
                                    scrollTopArea && reduce -> {
                                        Log.i(TAG, "scrollTopArea")
                                        onUnSelect?.invoke(
                                            rv,
                                            touchPos
                                        )
                                        preMoveX = moveX
                                        preMoveY = moveY
                                    }
                                    increase -> {
                                        Log.i(TAG, "increase touchPos = $touchPos")
                                        onSelect?.invoke(rv, touchPos)
                                        preMoveX = moveX
                                        preMoveY = moveY
                                        isIncreasing = true
                                    }
                                    reduce -> {
                                        Log.i(TAG, "reduce touchPos = $touchPos")
                                        onUnSelect?.invoke(rv, touchPos)
                                        preMoveX = moveX
                                        preMoveY = moveY
                                        isIncreasing = false
                                    }
                                }
                                if (((scrollBottomArea && isIncreasing) || (scrollTopArea && !isIncreasing)) && isLongPress) {
                                    Log.i(TAG, "isLongPress = true")
                                    var targetPos = if (scrollBottomArea) adapter!!.itemCount - 1 else 0
                                    rv.stopScroll()
                                    rv.smoothScrollToPosition(targetPos)
                                    autoScroll?.let { it1 -> handle.removeCallbacks(it1) }
                                    val runnable = object : Runnable {
                                        override fun run() {
                                            if (System.currentTimeMillis() - latestMoveTs >= ((1000 / refreshRate).toLong()) && ((scrollBottomArea && isIncreasing) || (scrollTopArea && !isIncreasing)) && isLongPress) {
                                                Log.i(
                                                    TAG,
                                                    "rvGestureDetector onScroll detected event lost, manually scroll scrollBottomArea = $scrollBottomArea, isIncreasing = $isIncreasing"
                                                )
                                                targetPos =
                                                    if (scrollBottomArea) adapter!!.itemCount - 1 else 0
                                                latestMoveTs = System.currentTimeMillis()
                                                if (scrollBottomArea) {
                                                    onSelect?.invoke(
                                                        rv,
                                                        if (leftArea) gridLayoutManager.findLastVisibleItemPosition() else gridLayoutManager.findLastVisibleItemPosition() + 4
                                                    )
                                                } else {
                                                    onUnSelect?.invoke(rv, touchPos)
                                                }
                                            }
                                            handle.postDelayed(this, (1000 / refreshRate).toLong())
                                        }
                                    }
                                    autoScroll = runnable
                                    handle.postDelayed(runnable, (1000 / refreshRate).toLong())
                                } else {
                                    rv.stopScroll()
                                }
                            }
                            preMoveX = event.x
                            preMoveY = event.y
                        }
                        MotionEvent.ACTION_CANCEL, MotionEvent.ACTION_UP -> {
                            Log.i(TAG, "ACTION_CANCEL or ACTION_UP")
                            rv.stopScroll()
                            isLongPress = false
                            isAutoScrolling = false
                            isInAutoScrollArea = false
                            preMoveX = 0f
                            preMoveY = 0f
                            autoScroll?.let { it1 -> handle.removeCallbacks(it1) }
                        }
                    }
                }

                override fun onRequestDisallowInterceptTouchEvent(disallowIntercept: Boolean) {}

            })
        }

    }
}