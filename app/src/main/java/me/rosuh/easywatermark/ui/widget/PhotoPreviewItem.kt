package me.rosuh.easywatermark.ui.widget

import android.content.Context
import android.util.AttributeSet
import android.util.Log
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import androidx.core.view.children
import androidx.core.view.setPadding
import androidx.interpolator.view.animation.FastOutSlowInInterpolator
import me.rosuh.easywatermark.R
import me.rosuh.easywatermark.utils.VibrateHelper
import me.rosuh.easywatermark.utils.ktx.appear
import me.rosuh.easywatermark.utils.ktx.dp

class PhotoPreviewItem : ViewGroup {
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

    private var isLongPress = false

    val ivIcon: ImageView by lazy {
        ImageView(context).apply {
            id = View.generateViewId()
            isLongPress = false
            layoutParams =
                MarginLayoutParams(40.dp, 40.dp)
            scaleType = ImageView.ScaleType.CENTER_CROP
            setOnLongClickListener {
                if ((parent.parent as ViewGroup).childCount <= 1) return@setOnLongClickListener false
                Log.i("gestureDetectorCompat", "long click")
                isLongPress = true
                VibrateHelper.get().doVibrate(it)
                ivDel.appear(duration = 150L)
                return@setOnLongClickListener false
            }
            setOnTouchListener(object : OnTouchListener {
                private var x = 0f
                private var y = 0f
                private var curIsPreview = false

                override fun onTouch(view: View, motionEvent: MotionEvent?): Boolean {
                    var isRemoved = false
                    when (motionEvent?.actionMasked) {
                        MotionEvent.ACTION_DOWN -> {
                            x = motionEvent.rawX
                            y = motionEvent.rawY
                            Log.i(
                                "gestureDetectorCompat",
                                "onTouch event = down, isLongPress = $isLongPress"
                            )
                            view.parent.requestDisallowInterceptTouchEvent(isLongPress)
                        }
                        MotionEvent.ACTION_MOVE -> {
                            if (!isLongPress) return false
                            val topEdge = ivDel.top.toFloat()
                            view.translationZ
                            view.translationY =
                                (motionEvent.rawY - y).coerceAtMost(0f)
                                    .coerceAtLeast(topEdge)
                            view.parent.requestDisallowInterceptTouchEvent(isLongPress)
                            Log.i(
                                "gestureDetectorCompat",
                                "onTouch event = move, isLongPress = $isLongPress"
                            )
                            curIsPreview = if (view.translationY == topEdge && !curIsPreview) {
                                onRemovePreview.invoke()
                                true
                            } else {
                                false
                            }
                        }
                        MotionEvent.ACTION_UP -> {
                            if (!isLongPress) {
                                post { this@PhotoPreviewItem.performClick() }
                            }
                            if (isLongPress && view.translationY <= ivDel.top.toFloat()) {
                                isRemoved = true
                                view.animate()
                                    .alpha(0f)
                                    .translationX(0f)
                                    .setDuration(200)
                                    .setInterpolator(FastOutSlowInInterpolator())
                                    .withStartAction {
                                        ivDel.animate().alpha(0f).setDuration(200L).start()
                                    }
                                    .withEndAction {
                                        view.apply {
                                            translationY = 0f
                                            translationX = 0f
                                            translationZ = 0f
                                        }
                                        onRemove.invoke()
                                    }
                                    .start()
                            } else {
                                ivDel.animate()
                                    .alpha(0f)
                                    .setDuration(150L)
                                    .withStartAction {
                                        onRemoveCancel.invoke()
                                    }
                                    .start()
                            }
                            isLongPress = false
                            curIsPreview = false
                            view.parent.requestDisallowInterceptTouchEvent(isLongPress)
                            (view.parent as ViewGroup).translationZ = 0f
                            Log.i(
                                "gestureDetectorCompat",
                                "onTouch event = up, isLongPress = $isLongPress"
                            )
                        }
                    }
                    if (motionEvent?.actionMasked != MotionEvent.ACTION_MOVE && !isRemoved) {
                        view.animate().translationX(0f).translationY(0f).translationZ(0f)
                            .setDuration(150).setInterpolator(FastOutSlowInInterpolator()).start()
                    }
                    return isLongPress
                }
            })
        }
    }

    private var onRemovePreview: () -> Unit = {
        Log.i("gestureDetectorCompat", "onRemovePreview")
    }
    private var onRemove: () -> Unit = {
        Log.i("gestureDetectorCompat", "onRemove")
    }
    private var onRemoveCancel: () -> Unit = {
        Log.i("gestureDetectorCompat", "onRemoveCancel")
    }

    fun onRemovePreview(block: () -> Unit = {}) {
        this.onRemovePreview = block
    }

    fun onRemove(block: () -> Unit = {}) {
        this.onRemove = block
    }

    fun onRemoveCancel(block: () -> Unit = {}) {
        this.onRemoveCancel = block
    }

    val ivDel: ImageView by lazy {
        ImageView(context).apply {
            id = View.generateViewId()
            layoutParams =
                MarginLayoutParams(48.dp, 48.dp)
            scaleType = ImageView.ScaleType.CENTER_CROP
            setPadding(8.dp)
            setBackgroundResource(R.drawable.bg_removed_photo_list)
            setImageResource(R.drawable.ic_remove_item)
        }
    }

    init {
        clipToPadding = false
        clipChildren = false
        addView(ivDel)
        addView(ivIcon)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        children.forEach {
            measureChildWithMargins(it, widthMeasureSpec, 0, heightMeasureSpec, 0)
        }
        setMeasuredDimension(
            MeasureSpec.makeMeasureSpec(48.dp, MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(48.dp, MeasureSpec.EXACTLY)
        )
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        ivIcon.let {
            it.layout(
                (measuredWidth - it.measuredWidth) / 2,
                (measuredHeight - it.measuredHeight) / 2,
                (measuredWidth - it.measuredWidth) / 2 + it.measuredWidth,
                (measuredHeight - it.measuredHeight) / 2 + it.measuredHeight
            )
        }
        ivDel.let {
            it.layout(
                (measuredWidth - it.measuredWidth) / 2,
                (-1.2 * ivDel.measuredHeight).toInt(),
                (measuredWidth - it.measuredWidth) / 2 + it.measuredWidth,
                (-1.2 * ivDel.measuredHeight).toInt() + it.measuredHeight
            )
        }
    }
}