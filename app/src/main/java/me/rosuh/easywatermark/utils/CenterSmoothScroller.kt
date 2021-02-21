package me.rosuh.easywatermark.utils

import android.content.Context
import androidx.recyclerview.widget.LinearSmoothScroller

class CenterSmoothScroller : LinearSmoothScroller {

    constructor(context: Context?) : super(context)

    override fun calculateDtToFit(
        viewStart: Int,
        viewEnd: Int,
        boxStart: Int,
        boxEnd: Int,
        snapPreference: Int
    ): Int {
        return boxStart + (boxEnd - boxStart) / 2 - (viewStart + (viewEnd - viewStart) / 2)
    }
}