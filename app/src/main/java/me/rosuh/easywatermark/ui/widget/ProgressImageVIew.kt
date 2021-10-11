package me.rosuh.easywatermark.ui.widget

import android.animation.ObjectAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.*
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.util.AttributeSet
import androidx.appcompat.widget.AppCompatImageView
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.toBitmap
import androidx.core.graphics.withSave
import androidx.core.view.isVisible
import me.rosuh.easywatermark.R
import kotlin.random.Random

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

    private var innerBitmap: Bitmap? = null

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        sizeHasChanged = w != oldh || h != oldh
    }

    private val saveLayerBounds = RectF()

    @SuppressLint("DrawAllocation")
    override fun onDraw(canvas: Canvas?) {
        super.onDraw(canvas)
        if (measuredWidth < 0 || measuredHeight <= 0 || drawable == null || drawable.intrinsicWidth <= 0 || drawable.intrinsicHeight <= 0) {
            return
        }
        if (curX <= 0f) return
        setupLayerBounds()
        innerBitmap = drawable.toBitmap()
        canvas?.withSave {
            canvas.drawBitmap(innerBitmap!!, saveLayerBounds.left, saveLayerBounds.top, paint)
            paint.xfermode = xfermode
            canvas.drawRect(0f, 0f, curX, height.toFloat(), paint)
            paint.xfermode = null
        }
    }

    private fun setupLayerBounds() {
        val bounds = saveLayerBounds
        val drawable: Drawable = drawable
        imageMatrix.mapRect(bounds, RectF(drawable.bounds))
        bounds.set(
            bounds.left + paddingLeft,
            bounds.top + paddingTop,
            bounds.right + paddingRight,
            bounds.bottom + paddingBottom,
        )
    }

    fun start() {
        isVisible = true
        startAnimator.cancel()
        startAnimator.setFloatValues(0f, 0.25f)
        startAnimator.start()
    }

    fun finish(animate: Boolean = true) {
        isVisible = true
        val curValue = startAnimator.animatedValue as Float
        startAnimator.cancel()
        if (animate) {
            startAnimator.setFloatValues(curValue, 1f)
            startAnimator.start()
        } else {
            post {
                curX = measuredWidth.toFloat()
                paint.color = successColor
                postInvalidateOnAnimation()
            }
        }
    }

    fun failed() {
        isVisible = true
        startAnimator.cancel()
        paint.color = failedColor
        curX = measuredWidth.toFloat()
        postInvalidateOnAnimation()
    }

    fun ready() {
        isVisible = true
        startAnimator.cancel()
        curX = 0f
        postInvalidateOnAnimation()
    }
}
