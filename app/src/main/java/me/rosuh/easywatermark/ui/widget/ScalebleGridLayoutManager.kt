package me.rosuh.easywatermark.ui.widget

import android.content.Context
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView

class ScalebleGridLayoutManager(
    context: Context?,
    spanCount: Int,
    private val viewGroupHeight: Int
) : GridLayoutManager(context, spanCount) {

    override fun onMeasure(
        recycler: RecyclerView.Recycler,
        state: RecyclerView.State,
        widthSpec: Int,
        heightSpec: Int
    ) {
        val lineCount = itemCount / spanCount + 1
        val maxLineHeight = if (lineCount <= 1) viewGroupHeight else viewGroupHeight / 2
        for (i in 0 until itemCount) {
            (recycler.getViewForPosition(i) as ConstraintLayout).maxHeight = maxLineHeight
        }
        super.onMeasure(recycler, state, widthSpec, heightSpec)
    }

    override fun isAutoMeasureEnabled(): Boolean {
        return false
    }
}