package me.rosuh.easywatermark.widget

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import androidx.core.graphics.withSave
import androidx.core.view.children
import androidx.recyclerview.widget.LinearSnapHelper
import androidx.recyclerview.widget.RecyclerView
import kotlin.math.abs
import kotlin.math.min

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

    private var childWidth = 0

    private var childHeight = 0

    private val glowRectF = RectF()
    private var glowRadius = 0f

    private val colorList = arrayOf(
        Color.parseColor("#00FFD703"),
        Color.parseColor("#1AFFD703"),
    ).toIntArray()


    private val glowPaint by lazy {
        Paint()
    }

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
        childWidth = children.firstOrNull()?.measuredWidth ?: 0
        childHeight = children.firstOrNull()?.measuredHeight ?: 0
        setPadding((measuredWidth - childWidth) / 2, 0, (measuredWidth - childWidth) / 2, 0)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        glowRectF.set(
            (measuredWidth - childWidth) / 2f,
            0f,
            (measuredWidth + childWidth) / 2f,
            childHeight.toFloat()
        )
        glowRadius = min(childWidth.toFloat(), childHeight.toFloat()) / 2
        if (glowRadius <= 0) {
            glowPaint.shader = null
            return
        }
        val shader = RadialGradient(
            (measuredWidth / 2).toFloat(),
            (measuredHeight / 2).toFloat(),
            glowRadius,
            colorList,
            null,
            Shader.TileMode.CLAMP
        )
        glowPaint.shader = shader
    }

    override fun onDraw(c: Canvas?) {
        super.onDraw(c)
        c?.withSave {
            drawRect(
                glowRectF,
                glowPaint
            )
        }
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