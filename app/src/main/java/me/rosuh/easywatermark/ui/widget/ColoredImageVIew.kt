package me.rosuh.easywatermark.ui.widget

import android.animation.ObjectAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.*
import android.hardware.display.DisplayManager
import android.util.AttributeSet
import androidx.appcompat.widget.AppCompatImageView
import androidx.core.graphics.drawable.toBitmap
import me.rosuh.easywatermark.utils.ktx.colorPrimary
import me.rosuh.easywatermark.utils.ktx.colorSecondary
import me.rosuh.easywatermark.utils.ktx.colorTertiary
import me.rosuh.easywatermark.utils.ktx.supportDynamicColor

class ColoredImageVIew : AppCompatImageView {
    constructor(context: Context) : super(context)
    constructor(context: Context, attrs: AttributeSet?) : super(context, attrs)
    constructor(context: Context, attrs: AttributeSet?, defStyleAttr: Int) : super(
        context,
        attrs,
        defStyleAttr
    )

    private var refreshRate: Float = 60F
    private var sizeHasChanged: Boolean = true
    private val paint by lazy { Paint() }
    var enable = true

    private val colorList = if (context.supportDynamicColor()) {
        arrayOf(
            context.colorPrimary,
            context.colorSecondary,
            context.colorTertiary,
            context.colorTertiary,
        ).toIntArray()
    } else {
        arrayOf(
            Color.parseColor("#FFA51F"),
            Color.parseColor("#FFD703"),
            Color.parseColor("#C0FF39"),
            Color.parseColor("#00FFE0")
        ).toIntArray()
    }

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
                    postInvalidateDelayed((1000 / refreshRate).toLong())
                }
                duration = 2500
                repeatCount = ObjectAnimator.INFINITE
                repeatMode = ObjectAnimator.REVERSE
            }
    }

    private var innerBitmap: Bitmap? = null

    init {
        val displayManager: DisplayManager = context.applicationContext.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
        refreshRate = displayManager.displays?.getOrNull(0)?.refreshRate ?: 60F
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        sizeHasChanged = w != oldh || h != oldh
    }

    @SuppressLint("DrawAllocation")
    override fun onDraw(canvas: Canvas) {
        if (innerBitmap == null || (sizeHasChanged && width > 0 && height > 0)) {
            super.onDraw(canvas)
        }
        if (measuredWidth + measuredHeight <= 0) {
            return
        }
        innerBitmap = drawable.toBitmap(measuredWidth, measuredHeight)
        sizeHasChanged = false
        innerBitmap?.let {
            val sc = canvas?.saveLayer(0f, 0f, width.toFloat(), height.toFloat(), null) ?: return
            canvas.drawBitmap(it, 0f, 0f, paint)
            paint.xfermode = xfermode
            canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), paint)
            paint.xfermode = null
            canvas.restoreToCount(sc)
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (enable) {
            colorAnimator.start()
        }
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        colorAnimator.pause()
    }

    fun start() {
        enable = true
        colorAnimator.start()
    }

    fun stop() {
        enable = false
        colorAnimator.cancel()
    }
}
