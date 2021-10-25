package me.rosuh.easywatermark.data.model

import android.graphics.Matrix
import android.widget.ImageView

data class ViewInfo(
    val width: Int,
    val height: Int,
    val paddingLeft: Int,
    val paddingTop: Int,
    val paddingRight: Int,
    val paddingBottom: Int,
    val scaleType: ImageView.ScaleType,
    val matrix: Matrix,
) {
    companion object {
        fun from(imageView: ImageView): ViewInfo {
            return ViewInfo(
                imageView.width,
                imageView.height,
                imageView.paddingLeft,
                imageView.paddingTop,
                imageView.paddingRight,
                imageView.paddingBottom,
                imageView.scaleType,
                imageView.matrix
            )
        }
    }
}
