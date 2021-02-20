package me.rosuh.easywatermark.utils

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF

object ImageHelper {
    fun draw(
        canvas: Canvas?,
        layoutPaint: Paint,
        drawableBounds: RectF
    ) {
        val saveCount = canvas?.saveCount
        canvas?.save()
        canvas?.translate(drawableBounds.left, drawableBounds.top)
        canvas?.drawRect(
            0f,
            0f,
            drawableBounds.right - drawableBounds.left,
            drawableBounds.bottom - drawableBounds.top,
            layoutPaint
        )
        canvas?.restoreToCount(saveCount!!)
    }
}