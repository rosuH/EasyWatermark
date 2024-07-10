package me.rosuh.easywatermark.ui.widget

import android.animation.ObjectAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.util.AttributeSet
import androidx.appcompat.widget.AppCompatImageView
import androidx.core.view.isVisible
import com.google.android.material.color.MaterialColors
import me.rosuh.easywatermark.utils.ktx.colorError
import me.rosuh.easywatermark.utils.ktx.colorTertiary
import java.util.concurrent.atomic.AtomicBoolean

class ProgressImageView : AppCompatImageView {
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

    private val successColor = MaterialColors.compositeARGBWithAlpha(context.colorTertiary, 125)
    private val failedColor = context.colorError

    private val enableProgress by lazy { AtomicBoolean(false) }

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
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if ((measuredWidth < 0 || measuredHeight <= 0 || drawable == null
                || drawable.intrinsicWidth <= 0 || drawable.intrinsicHeight <= 0) || curX <= 0f || !enableProgress.get()
        ) {
            return
        }
        setupLayerBounds()
        canvas.drawRect(
            saveLayerBounds.left,
            saveLayerBounds.top,
            curX.coerceAtLeast(saveLayerBounds.left).coerceAtMost(saveLayerBounds.right),
            saveLayerBounds.bottom,
            paint
        )
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
        enableProgress.set(true)
        isVisible = true
        startAnimator.cancel()
        startAnimator.setFloatValues(0f, 0.25f)
        startAnimator.start()
    }

    fun finish(animate: Boolean = true) {
        enableProgress.set(true)
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
                invalidate()
            }
        }
    }

    fun failed() {
        enableProgress.set(true)
        isVisible = true
        startAnimator.cancel()
        paint.color = failedColor
        curX = measuredWidth.toFloat()
        invalidate()
    }

    fun ready() {
        enableProgress.set(false)
        startAnimator.cancel()
        curX = 0f
        isVisible = true
        postInvalidate()
    }
}
