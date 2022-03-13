package me.rosuh.easywatermark.ui.widget

import android.content.Context
import android.util.AttributeSet
import android.view.View
import androidx.asynclayoutinflater.view.AsyncLayoutInflater
import me.rosuh.easywatermark.R

class AsyncSquareFrameLayout : SquareFrameLayout {

    constructor(context: Context) : super(context)
    constructor(context: Context, attrs: AttributeSet?) : super(context, attrs)
    constructor(context: Context, attrs: AttributeSet?, defStyleAttr: Int) : super(
        context,
        attrs,
        defStyleAttr
    )

    constructor(
        context: Context,
        attrs: AttributeSet?,
        defStyleAttr: Int,
        defStyleRes: Int
    ) : super(context, attrs, defStyleAttr, defStyleRes)


    fun inflate(action: (view: View) -> Unit) {
        AsyncLayoutInflater(context).inflate(R.layout.item_image_gallery, this) { view, _, _ ->
            addView(view)
            action.invoke(view)
        }
    }

}