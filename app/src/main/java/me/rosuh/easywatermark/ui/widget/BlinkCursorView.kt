package me.rosuh.easywatermark.ui.widget

import android.animation.ObjectAnimator
import android.content.Context
import android.util.AttributeSet
import android.view.View
import android.view.animation.AlphaAnimation

class BlinkCursorView : View {

    constructor(context: Context?) : super(context)
    constructor(context: Context?, attrs: AttributeSet?) : super(context, attrs)
    constructor(context: Context?, attrs: AttributeSet?, defStyleAttr: Int) : super(
        context,
        attrs,
        defStyleAttr
    )

    private val alphaAnimation by lazy {
        AlphaAnimation(1f, 0f).apply {
            duration = 400
            repeatCount = ObjectAnimator.INFINITE
            repeatMode = ObjectAnimator.REVERSE
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        startBlink()
    }

    private fun startBlink() {
        if (this.animation == null) {
            this.startAnimation(alphaAnimation)
        }
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        this.clearAnimation()
    }
}