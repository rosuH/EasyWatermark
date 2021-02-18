package me.rosuh.easywatermark.widget

import android.content.Context
import android.graphics.Rect
import android.util.AttributeSet
import android.view.View
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView

class SpaceHeaderItemDecoration(
    private val space: Int = 0
) : RecyclerView.ItemDecoration() {

    override fun getItemOffsets(
        outRect: Rect,
        view: View,
        parent: RecyclerView,
        state: RecyclerView.State
    ) {
        val pos = parent.getChildAdapterPosition(view)
        val isFirstItem = pos == 0
        val isLastItem = pos == state.itemCount - 1
        if (!isFirstItem && !isLastItem) {
            return
        }
        when (parent.layoutDirection) {
            RecyclerView.HORIZONTAL -> {
                when {
                    isFirstItem -> {
                        outRect.left = space - view.width / 2
                    }
                    isLastItem -> {
                        outRect.right = space - view.width / 2
                    }
                }
            }
            RecyclerView.VERTICAL -> {
                when {
                    isFirstItem -> {
                        outRect.top = space - view.height / 2
                    }
                    isLastItem -> {
                        outRect.bottom = space - view.height / 2
                    }
                }

            }
        }
    }
}