package me.rosuh.easywatermark.ui.widget

import android.animation.ObjectAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import androidx.appcompat.widget.AppCompatImageView
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.toBitmap
import me.rosuh.easywatermark.R

class ProgressImageVIew : AppCompatImageView {
    constructor(context: Context) : super(context)
    constructor(context: Context, attrs: AttributeSet?) : super(context, attrs)
    constructor(context: Context, attrs: AttributeSet?, defStyleAttr: Int) : super(
        context,
        attrs,
        defStyleAttr
    )

    private var sizeHasChanged: Boolean = true

    private val paint by lazy {
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = successColor
        }
    }

    private val successColor = ContextCompat.getColor(context, R.color.d_progress_active)
    private val failedColor = ContextCompat.getColor(context, R.color.d_progress_error)

    private val xfermode by lazy { PorterDuffXfermode(PorterDuff.Mode.SRC_ATOP) }

    private var curX = 0f

    private val startAnimator by lazy {
        ObjectAnimator.ofFloat(0f, 0.25f)
            .apply {
                addUpdateListener {
                    paint.color = successColor
                    curX = (it.animatedValue as Float) * measuredWidth
                    postInvalidateOnAnimation()
                }
            }
    }

    private val finishAnimator by lazy {
        ObjectAnimator.ofFloat(1f)
            .apply {
                addUpdateListener {
                    paint.color = successColor
                    curX = (it.animatedValue as Float) * measuredWidth
                    postInvalidateOnAnimation()
                }
            }
    }

    private var innerBitmap: Bitmap? = null

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        sizeHasChanged = w != oldh || h != oldh
    }

    @SuppressLint("DrawAllocation")
    override fun onDraw(canvas: Canvas?) {
        if (innerBitmap == null || (sizeHasChanged && width > 0 && height > 0)) {
            super.onDraw(canvas)
        }
        if (measuredWidth + measuredHeight <= 0 || drawable == null || curX <= 0) {
            return
        }
        innerBitmap = drawable.toBitmap(measuredWidth, measuredHeight)
        sizeHasChanged = false
        innerBitmap?.let {
            val sc = canvas?.saveLayer(0f, 0f, width.toFloat(), height.toFloat(), null) ?: return
            canvas.drawBitmap(it, 0f, 0f, paint)
            paint.xfermode = xfermode
            canvas.drawRect(0f, 0f, curX, height.toFloat(), paint)
            paint.xfermode = null
            canvas.restoreToCount(sc)
        }
    }

    fun start() {
        startAnimator.cancel()
        finishAnimator.cancel()
        startAnimator.start()
    }

    fun finish() {
        startAnimator.cancel()
        finishAnimator.start()
    }

    fun failed(){
        startAnimator.cancel()
        finishAnimator.cancel()
        paint.color = failedColor
        curX = measuredWidth.toFloat()
        postInvalidateOnAnimation()
    }

    fun ready() {
        startAnimator.cancel()
        finishAnimator.cancel()
        curX = 0f
        postInvalidateOnAnimation()
    }
}
