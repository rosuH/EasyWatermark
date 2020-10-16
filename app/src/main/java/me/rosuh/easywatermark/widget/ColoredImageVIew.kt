package me.rosuh.easywatermark.widget

import android.animation.ObjectAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import androidx.appcompat.widget.AppCompatImageView
import androidx.core.graphics.drawable.toBitmap


class ColoredImageVIew : AppCompatImageView {
    constructor(context: Context) : super(context)
    constructor(context: Context, attrs: AttributeSet?) : super(context, attrs)
    constructor(context: Context, attrs: AttributeSet?, defStyleAttr: Int) : super(
        context,
        attrs,
        defStyleAttr
    )

    private var sizeHasChanged: Boolean = true
    private val paint by lazy { Paint() }

    private val colorList = arrayOf(
        Color.parseColor("#FFA51F"),
        Color.parseColor("#FFD703"),
        Color.parseColor("#C0FF39"),
        Color.parseColor("#00FFE0")
    ).toIntArray()

    private val posList = arrayOf(0f, 0.5f, 0.7f, 0.99f).toFloatArray()

    private val xfermode by lazy { PorterDuffXfermode(PorterDuff.Mode.SRC_ATOP) }

    private val colorAnimator by lazy {
        ObjectAnimator.ofFloat(1f, 0.1f)
            .apply {
                addUpdateListener {
                    val pos = (it.animatedValue as Float)
                    val shader = LinearGradient(
                        (1.1f - pos) * width.toFloat() * 2f,
                        pos * height.toFloat(),
                        0f,
                        height.toFloat(),
                        colorList,
                        posList,
                        Shader.TileMode.CLAMP
                    )
                    paint.shader = shader
                    postInvalidateOnAnimation()
                }
                duration = 2500
                repeatCount = ObjectAnimator.INFINITE
                repeatMode = ObjectAnimator.REVERSE
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
            innerBitmap = drawable.toBitmap(width, height)
            sizeHasChanged = false
        }
        innerBitmap?.let {
            val sc = canvas?.saveLayer(0f, 0f, width.toFloat(), height.toFloat(), null) ?: return
            canvas.drawBitmap(it, 0f, 0f, paint)
            paint.xfermode = xfermode
            canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), paint)
            paint.xfermode = null
            canvas.restoreToCount(sc)
        }
    }


    fun start() {
        colorAnimator.start()
    }

    fun stop() {
        colorAnimator.pause()
    }
}