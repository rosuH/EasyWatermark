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
                Log.i("gestureDetectorCompat", "long click")
                isLongPress = true
                VibrateHelper.get().doVibrate(it)
                ivDel.appear()
                return@setOnLongClickListener false
            }
            setOnTouchListener(object : OnTouchListener {
                private var x = 0f
                private var y = 0f

                override fun onTouch(view: View, motionEvent: MotionEvent?): Boolean {
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
                            view.translationZ
                            view.translationY =
                                (motionEvent.rawY - y).coerceAtMost(0f)
                                    .coerceAtLeast(ivDel.top.toFloat())
                            view.parent.requestDisallowInterceptTouchEvent(isLongPress)
                            Log.i(
                                "gestureDetectorCompat",
                                "onTouch event = move, isLongPress = $isLongPress"
                            )
                            if (view.translationY == ivDel.top.toFloat()) {
                                onRemovePreview.invoke()
                            }
                        }
                        MotionEvent.ACTION_UP -> {
                            if (!isLongPress) {
                                post { this@PhotoPreviewItem.performClick() }
                            }
                            if (isLongPress && view.translationY <= ivDel.top.toFloat()) {
                                onRemove.invoke()
                            } else {
                                onRemoveCancel.invoke()
                            }
                            isLongPress = false
                            view.parent.requestDisallowInterceptTouchEvent(isLongPress)
                            (view.parent as ViewGroup).translationZ = 0f
                            Log.i(
                                "gestureDetectorCompat",
                                "onTouch event = up, isLongPress = $isLongPress"
                            )
                            ivDel.animate().alpha(0f).start()
                        }
                    }
                    if (motionEvent?.actionMasked != MotionEvent.ACTION_MOVE) {
                        view.animate().translationX(0f).translationY(0f).translationZ(0f)
                            .setDuration(200).start()
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
                MarginLayoutParams(56.dp, 56.dp)
            scaleType = ImageView.ScaleType.CENTER_CROP
            setBackgroundResource(R.drawable.bg_removed_photo_list)
            setPadding(5.dp)
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
            MeasureSpec.makeMeasureSpec(56.dp, MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(56.dp, MeasureSpec.EXACTLY)
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
                (-1.5 * ivIcon.measuredHeight).toInt(),
                (measuredWidth - it.measuredWidth) / 2 + it.measuredWidth,
                (-1.5 * ivIcon.measuredHeight).toInt() + it.measuredHeight
            )
        }
    }
}