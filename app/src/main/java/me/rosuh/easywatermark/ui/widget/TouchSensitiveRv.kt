package me.rosuh.easywatermark.ui.widget

import android.animation.ObjectAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.util.AttributeSet
import android.util.TypedValue
import android.view.MotionEvent
import android.view.View
import androidx.core.graphics.ColorUtils
import androidx.core.graphics.withSave
import androidx.core.view.children
import androidx.recyclerview.widget.LinearSnapHelper
import androidx.recyclerview.widget.RecyclerView
import me.rosuh.easywatermark.R
import me.rosuh.easywatermark.utils.ktx.colorPrimary
import me.rosuh.easywatermark.utils.ktx.dp
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

    var childWidth = 0
        private set

    var childHeight = 0
        private set

    var canTouch: Boolean = true

    var enableBorder = false

    private val glowRectF = RectF()
    private var glowRadius = 0f

    private val borderRectF = RectF()
    private val borderSize = 48F.dp

    private val colorList = arrayOf(
        ColorUtils.setAlphaComponent(context.colorPrimary, 0),
        ColorUtils.setAlphaComponent(context.colorPrimary, 15),
    ).toIntArray()

    private val glowPaint by lazy {
        Paint()
    }

    private val borderWidth = 3.dp.toFloat()

    private val colorPrimary by lazy {
        val typedValue = TypedValue()
        context.theme.resolveAttribute(R.attr.colorPrimary, typedValue, true)
        typedValue.data
    }

    private val colorPrimaryDark by lazy {
        val typedValue = TypedValue()
        context.theme.resolveAttribute(R.attr.colorPrimary, typedValue, true)
        typedValue.data
    }

    private val colorAccent by lazy {
        val typedValue = TypedValue()
        context.theme.resolveAttribute(R.attr.colorAccent, typedValue, true)
        typedValue.data
    }

    private val borderPaint by lazy {
        Paint().apply {
            color = colorAccent
            style = Paint.Style.STROKE
            strokeWidth = 1.5F.dp
            isAntiAlias = true
            isDither = true
        }
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
        addOnScrollListener(object : OnScrollListener() {
            private var debounceTs = 0L
            override fun onScrollStateChanged(recyclerView: RecyclerView, newState: Int) {
                super.onScrollStateChanged(recyclerView, newState)
                val snapView = snapHelper.findSnapView(layoutManager)
                when (newState) {
                    SCROLL_STATE_IDLE -> {
                        borderAnimator.start()
                        if (snapView == null ||
                            !canAutoSelected ||
                            System.currentTimeMillis() - debounceTs < 300
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
        borderRectF.set(
            (measuredWidth - borderSize) / 2f,
            0f,
            (measuredWidth + borderSize) / 2f,
            borderSize
        )
        glowRectF.set(
            (measuredWidth - childWidth) / 2f,
            0f,
            (measuredWidth + childWidth) / 2f,
            childHeight.toFloat()
        )
        glowRadius = borderSize / 2
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

    private val borderAnimator = ObjectAnimator.ofInt(0, 255).apply {
        addUpdateListener {
            if (!enableBorder) return@addUpdateListener
            borderPaint.alpha = it.animatedValue as Int
            postInvalidateOnAnimation()
        }
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        borderAnimator.cancel()
    }

    override fun onDraw(c: Canvas) {
        super.onDraw(c)
        c.withSave {
            drawRect(
                glowRectF,
                glowPaint
            )
        }
        if (enableBorder && scrollState == SCROLL_STATE_IDLE) {
            c.drawRoundRect(borderRectF, 2F.dp, 2F.dp, borderPaint)
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
