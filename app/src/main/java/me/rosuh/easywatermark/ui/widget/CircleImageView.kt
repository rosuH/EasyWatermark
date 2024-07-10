package me.rosuh.easywatermark.ui.widget

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import androidx.appcompat.widget.AppCompatImageView
import androidx.core.graphics.drawable.toBitmap
import kotlin.math.min

class CircleImageView : AppCompatImageView {
    constructor(context: Context) : super(context)
    constructor(context: Context, attrs: AttributeSet?) : super(context, attrs)
    constructor(context: Context, attrs: AttributeSet?, defStyleAttr: Int) : super(
        context,
        attrs,
        defStyleAttr
    )

    private var sizeHasChanged: Boolean = true

    private val paint by lazy {
        Paint().apply {
            isAntiAlias = true
            isDither = true
            color = Color.RED
        }
    }

    private val xfermode by lazy { PorterDuffXfermode(PorterDuff.Mode.SRC_ATOP) }

    private var sourceImageBitmap: Bitmap? = null

    private var destCircleBitmap: Bitmap? = null

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        sizeHasChanged = w != oldh || h != oldh
        if (!sizeHasChanged) {
            return
        }
        if (destCircleBitmap?.isRecycled != true) {
            destCircleBitmap?.recycle()
        }
        destCircleBitmap = makeCircleFrame(w, h)
    }

    private fun makeCircleFrame(w: Int, h: Int): Bitmap? {
        val bm = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bm)
        val path = Path()
        path.addCircle(
            (w / 2).toFloat(),
            ((h / 2).toFloat()),
            min(w / 2, h / 2).toFloat(),
            Path.Direction.CW
        )
        c.drawPath(path, paint)
        return bm
    }

    @SuppressLint("DrawAllocation")
    override fun onDraw(canvas: Canvas) {
        if (sourceImageBitmap == null || (sizeHasChanged && width > 0 && height > 0)) {
            super.onDraw(canvas)
            sourceImageBitmap = drawable.toBitmap(width, height)
            sizeHasChanged = false
            invalidate()
        }
        if (destCircleBitmap == null) {
            destCircleBitmap = makeCircleFrame(width, height)
        }
        sourceImageBitmap?.let {
            val sc = canvas?.saveLayer(0f, 0f, width.toFloat(), height.toFloat(), null) ?: return
            canvas.drawBitmap(destCircleBitmap!!, 0f, 0f, paint)
            paint.xfermode = xfermode
            canvas.drawBitmap(it, 0f, 0f, paint)
            paint.xfermode = null
            canvas.restoreToCount(sc)
        }
    }
}
