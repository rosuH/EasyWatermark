package me.rosuh.easywatermark.ui.widget

import android.content.Context
import android.util.AttributeSet
import android.widget.ImageView
import androidx.core.view.children
import me.rosuh.easywatermark.R
import me.rosuh.easywatermark.utils.ktx.dp

class Toolbar : CustomViewGroup {

    constructor(context: Context?) : super(context)
    constructor(context: Context?, attrs: AttributeSet?) : super(context, attrs)
    constructor(context: Context?, attrs: AttributeSet?, defStyleAttr: Int) : super(
        context,
        attrs,
        defStyleAttr
    )

    constructor(
        context: Context?,
        attrs: AttributeSet?,
        defStyleAttr: Int,
        defStyleRes: Int
    ) : super(context, attrs, defStyleAttr, defStyleRes)

    val logoView: ColoredImageVIew by lazy {
        ColoredImageVIew(context).apply {
            layoutParams = MarginLayoutParams(48.dp, 48.dp)
            setImageResource(R.drawable.ic_log_transparent)
        }
    }

    val ivSelectedPhoto: ImageView by lazy {
        ImageView(context, null, 0, android.R.style.Widget_ActionButton).apply {
            layoutParams =
                MarginLayoutParams(
                    LayoutParams.WRAP_CONTENT,
                    LayoutParams.WRAP_CONTENT
                ).also { it.setMargins(0, 0, 8.dp, 0) }
            setImageResource(R.drawable.ic_picker_image)
        }
    }

    val ivSave: ImageView by lazy {
        ImageView(context, null, 0, android.R.style.Widget_ActionButton).apply {
            layoutParams =
                MarginLayoutParams(
                    LayoutParams.WRAP_CONTENT,
                    LayoutParams.WRAP_CONTENT
                ).also { it.setMargins(0, 0, 8.dp, 0) }
            setImageResource(R.drawable.ic_save)
        }
    }

    val ivGoAboutPage: ImageView by lazy {
        ImageView(context, null, 0, android.R.style.Widget_ActionButton).apply {
            layoutParams =
                MarginLayoutParams(
                    LayoutParams.WRAP_CONTENT,
                    LayoutParams.WRAP_CONTENT
                ).also { it.setMargins(0, 0, 8.dp, 0) }
            setImageResource(R.drawable.ic_about)
        }
    }

    init {
        addView(logoView)
        addView(ivSelectedPhoto)
        addView(ivSave)
        addView(ivGoAboutPage)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
        children.forEach { measureChildWithMargins(it, widthMeasureSpec, 0, heightMeasureSpec, 0) }
        setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), 48.dp)
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        logoView.let {
            it.layoutCenterVertical()
        }
        ivGoAboutPage.let {
            it.layout(
                this.measuredWidth + this.paddingStart - it.measuredWidthWithMargins,
                (this.measuredHeight - this.measuredHeight) / 2
            )
        }
        ivSave.let {
            it.layout(
                ivGoAboutPage.left - it.measuredWidthWithMargins,
                (this.measuredHeight - this.measuredHeight) / 2
            )
        }
        ivSelectedPhoto.let {
            it.layout(
                ivSave.left - it.measuredWidthWithMargins,
                (this.measuredHeight - this.measuredHeight) / 2
            )
        }
    }
}
